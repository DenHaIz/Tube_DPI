/*
 * Резолвинг имён для byedpi через DNS-сервер приложения (127.0.0.1:<порт>, отвечает через DoH).
 *
 * С февраля 2026 youtube.com не резолвится через национальную систему DNS, которой пользуется
 * большинство провайдеров, поэтому системный getaddrinfo для byedpi бесполезен. Исходники byedpi
 * не меняем: getaddrinfo подменяется при линковке (-Wl,--wrap=getaddrinfo). Если переменная
 * окружения TUBE_DNS не задана, запрос числовой или ответа нет — вызывается настоящий getaddrinfo.
 *
 * Результат выделяется одним блоком (addrinfo + sockaddr), как в bionic, поэтому его корректно
 * освобождает штатный freeaddrinfo.
 */
#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

int __real_getaddrinfo(const char *node, const char *service,
                       const struct addrinfo *hints, struct addrinfo **res);

#define DNS_TYPE_A 1
#define DNS_TYPE_AAAA 28

static int encode_name(const char *name, uint8_t *out, size_t cap)
{
    size_t pos = 0;
    const char *label = name;
    while (*label) {
        const char *dot = strchr(label, '.');
        size_t len = dot ? (size_t)(dot - label) : strlen(label);
        if (len == 0 || len > 63 || pos + len + 2 > cap)
            return -1;
        out[pos++] = (uint8_t)len;
        memcpy(out + pos, label, len);
        pos += len;
        if (!dot)
            break;
        label = dot + 1;
    }
    if (pos + 1 > cap)
        return -1;
    out[pos++] = 0;
    return (int)pos;
}

/* Пропускает имя (метки или сжатый указатель) в ответе; возвращает новую позицию или -1. */
static int skip_name(const uint8_t *msg, int len, int pos)
{
    while (pos < len) {
        uint8_t c = msg[pos];
        if (c == 0)
            return pos + 1;
        if ((c & 0xC0) == 0xC0)
            return pos + 2 <= len ? pos + 2 : -1;
        pos += c + 1;
    }
    return -1;
}

/* Один запрос A или AAAA; 0 — адрес записан в out. */
static int query(const char *server, const char *name, int type, struct sockaddr_storage *out)
{
    char host[64];
    const char *colon = strrchr(server, ':');
    if (!colon || (size_t)(colon - server) >= sizeof(host))
        return -1;
    memcpy(host, server, colon - server);
    host[colon - server] = 0;

    struct sockaddr_in srv = { .sin_family = AF_INET, .sin_port = htons((uint16_t)atoi(colon + 1)) };
    if (inet_pton(AF_INET, host, &srv.sin_addr) != 1)
        return -1;

    uint8_t msg[512];
    /* arc4random: непредсказуемый id (rand() без seed одинаков во всех процессах byedpi). */
    uint16_t id = (uint16_t)(arc4random() & 0xFFFF);
    memset(msg, 0, 12);
    msg[0] = id >> 8;
    msg[1] = id & 0xFF;
    msg[2] = 0x01; /* RD */
    msg[5] = 1;    /* QDCOUNT */
    int n = encode_name(name, msg + 12, sizeof(msg) - 16);
    if (n < 0)
        return -1;
    int qlen = 12 + n;
    msg[qlen++] = 0;
    msg[qlen++] = (uint8_t)type;
    msg[qlen++] = 0;
    msg[qlen++] = 1; /* IN */

    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0)
        return -1;
    /* connect(): ядро отбрасывает датаграммы не от нашего DNS — чужое локальное приложение не подсунет ответ. */
    if (connect(fd, (struct sockaddr *)&srv, sizeof(srv)) < 0) {
        close(fd);
        return -1;
    }
    struct timeval tv = { .tv_sec = 2, .tv_usec = 500000 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    /* Две попытки по 2,5 с: LocalDns отвечает за ~4,5 с даже при недоступных DoH (в худшем случае — SERVFAIL). */
    uint8_t resp[1500];
    int len = -1;
    for (int attempt = 0; attempt < 2 && len < 0; attempt++) {
        if (send(fd, msg, qlen, 0) != qlen)
            break;
        for (;;) {
            int r = (int)recv(fd, resp, sizeof(resp), 0);
            if (r < 0)
                break;
            /* Только ответ (QR) на наш запрос (id). */
            if (r >= 12 && resp[0] == msg[0] && resp[1] == msg[1] && (resp[2] & 0x80)) {
                len = r;
                break;
            }
        }
    }

    int result = -1;
    {
        if (len >= 12 && (resp[3] & 0x0F) == 0) {
            int qd = (resp[4] << 8) | resp[5];
            int an = (resp[6] << 8) | resp[7];
            int pos = 12;
            for (int i = 0; i < qd && pos > 0; i++) {
                pos = skip_name(resp, len, pos);
                if (pos > 0)
                    pos += 4;
            }
            for (int i = 0; i < an && pos > 0 && pos < len; i++) {
                pos = skip_name(resp, len, pos);
                if (pos < 0 || pos + 10 > len)
                    break;
                int rtype = (resp[pos] << 8) | resp[pos + 1];
                int rdlen = (resp[pos + 8] << 8) | resp[pos + 9];
                pos += 10;
                if (pos + rdlen > len)
                    break;
                if (rtype == DNS_TYPE_A && type == DNS_TYPE_A && rdlen == 4) {
                    struct sockaddr_in *a = (struct sockaddr_in *)out;
                    memset(a, 0, sizeof(*a));
                    a->sin_family = AF_INET;
                    memcpy(&a->sin_addr, resp + pos, 4);
                    result = 0;
                    break;
                }
                if (rtype == DNS_TYPE_AAAA && type == DNS_TYPE_AAAA && rdlen == 16) {
                    struct sockaddr_in6 *a = (struct sockaddr_in6 *)out;
                    memset(a, 0, sizeof(*a));
                    a->sin6_family = AF_INET6;
                    memcpy(&a->sin6_addr, resp + pos, 16);
                    result = 0;
                    break;
                }
                pos += rdlen;
            }
        }
    }
    close(fd);
    return result;
}

static int is_numeric(const char *node)
{
    struct in6_addr tmp;
    return inet_pton(AF_INET, node, &tmp) == 1 || inet_pton(AF_INET6, node, &tmp) == 1;
}

int __wrap_getaddrinfo(const char *node, const char *service,
                       const struct addrinfo *hints, struct addrinfo **res)
{
    const char *server = getenv("TUBE_DNS");
    if (!server || !node || !res || is_numeric(node) || (hints && (hints->ai_flags & AI_NUMERICHOST)))
        return __real_getaddrinfo(node, service, hints, res);

    int family = hints ? hints->ai_family : AF_UNSPEC;
    struct sockaddr_storage addr;
    /* IPv4 сначала: IPv6 на мобильных сетях есть не везде. */
    int found = -1;
    if (family != AF_INET6)
        found = query(server, node, DNS_TYPE_A, &addr);
    if (found != 0 && family != AF_INET)
        found = query(server, node, DNS_TYPE_AAAA, &addr);
    if (found != 0)
        return __real_getaddrinfo(node, service, hints, res);

    socklen_t addrlen = addr.ss_family == AF_INET ? sizeof(struct sockaddr_in) : sizeof(struct sockaddr_in6);
    struct addrinfo *ai = calloc(1, sizeof(struct addrinfo) + sizeof(struct sockaddr_in6));
    if (!ai)
        return EAI_MEMORY;
    ai->ai_family = addr.ss_family;
    ai->ai_socktype = hints ? hints->ai_socktype : 0;
    ai->ai_protocol = hints ? hints->ai_protocol : 0;
    ai->ai_addrlen = addrlen;
    ai->ai_addr = (struct sockaddr *)(ai + 1);
    memcpy(ai->ai_addr, &addr, addrlen);
    if (service) {
        uint16_t port = htons((uint16_t)atoi(service));
        if (addr.ss_family == AF_INET)
            ((struct sockaddr_in *)ai->ai_addr)->sin_port = port;
        else
            ((struct sockaddr_in6 *)ai->ai_addr)->sin6_port = port;
    }
    *res = ai;
    return 0;
}
