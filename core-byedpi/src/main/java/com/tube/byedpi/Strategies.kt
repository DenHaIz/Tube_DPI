package com.tube.byedpi

/**
 * Готовая стратегия обхода. [args] — аргументы byedpi без адреса и порта; `{sni}` — фейковое имя сайта,
 * `@fake/<файл>` — фейковый ClientHello из zapret (см. [ByeDpiProcess]).
 */
data class Strategy(
    val id: String,
    val name: String,
    val args: String,
    val descriptionRu: String,
    val descriptionEn: String,
)

object Strategies {

    /** Свой вариант пользователя хранится под этим id. */
    const val CUSTOM_ID = "custom"

    /** Подстановка для фейкового SNI в аргументах. */
    const val SNI = "{sni}"

    /** Варианты фейкового SNI: в августе 2026 ТСПУ начал узнавать www.google.com, поэтому есть замены. */
    val FakeSniOptions = listOf("google.com", "ya.ru", "vk.com", "fonts.google.com")

    /**
     * Основной список: строки, о которых в 2026 писали как о рабочих у крупных провайдеров
     * (issues byedpi/ByeByeDPI, ntc.party), и аналоги general*.bat из Flowseal zapret-discord-youtube
     * в пределах возможностей byedpi без root.
     */
    val builtIn: List<Strategy> = listOf(
        Strategy(
            "rtk_multisplit", "MULTISPLIT OOB",
            "-o1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1",
            "OOB-байт и много разрезов вокруг SNI. Ростелеком, 2026",
            "OOB byte and many cuts around the SNI. Rostelecom, 2026",
        ),
        Strategy(
            "disorder_md5", "DISORDER MD5",
            "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1 -As -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -S -a1",
            "Перемешивание вокруг SNI + MD5-подпись фейков. ТТК, Ростелеком, Tele2",
            "Shuffling around the SNI plus MD5-signed fakes. TTK, Rostelecom, Tele2",
        ),
        Strategy(
            "disorder_tlsrec", "DISORDER TLSREC",
            "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1 -As",
            "Перемешивание и деление TLS-записи. Ростелеком, август 2026",
            "Shuffling plus TLS record split. Rostelecom, August 2026",
        ),
        Strategy(
            "disorder_lite", "DISORDER LITE",
            "-d1 -d3+s -s6+s -d9+s -s20+s -d25+s -s30+s -a1",
            "Облегчённое перемешивание без подделок. Проводной Ростелеком",
            "Lighter shuffling without fakes. Wired Rostelecom",
        ),
        Strategy(
            "beeline", "BEELINE",
            "-Kt,h -H:\"googlevideo.com youtube.com ytimg.com youtubei.googleapis.com\" -o1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1 -An -Kt,h -o1 -r-5+se -a1 -At,r,s -d1 -n $SNI -Qr -f-1 -r2 -a1",
            "Разрезы для доменов YouTube, для остальных — фейк с чужим SNI. Билайн",
            "Cuts for YouTube domains, fake with a foreign SNI for the rest. Beeline",
        ),
        Strategy(
            "mts", "MTS",
            "-Kt,h -o1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1 -At,r,s -d1 -Qr -f-1 -r2 -An",
            "Разрезы, при сбросе — фейк со случайными полями. МТС, МГТС",
            "Cuts, on reset a fake with random fields. MTS, MGTS",
        ),
        Strategy(
            "ttk", "TTK",
            "-o1 -d1 -a1 -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -S -a1 -As -s1 -d1 -s5+s -s10+s -s15+s -s20+s -S -a1",
            "Три ступени с MD5-подписью фейков. ТТК",
            "Three tiers with MD5-signed fakes. TTK",
        ),
        Strategy(
            "oob_fake_auto", "OOB + FAKE",
            "-o1 -r-5+se -a1 -At,r,s,c -d1 -n $SNI -Qr -f-1 -a1",
            "OOB и TLS-запись, при блокировке — фейк с чужим SNI",
            "OOB plus TLS record, on block a fake with a foreign SNI",
        ),
        Strategy(
            "ntc_combo", "NTC COMBO",
            "-Kt,h -H:\"googlevideo.com youtu.be ytimg.com youtubei.googleapis.com\" -d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1 -An -s1 -q1 -a1 -Y -Ar -a1 -s5 -o2 -At -f-1 -r1+s -a1 -As -s1 -o1+s -s-1 -a1 -An",
            "Многоступенчатая схема с ntc.party для Android",
            "Multi-tier scheme from ntc.party for Android",
        ),
        Strategy(
            "fake_tlsrec", "FAKE SNI",
            "-n $SNI -Qr -f-1 -r1+s -a1",
            "Фейковый ClientHello с чужим SNI и случайными полями",
            "Fake ClientHello with a foreign SNI and random fields",
        ),
        Strategy(
            "fake_split_ttl8", "FAKE TTL 8",
            "--fake -1 --ttl 8 --split 1+s --disorder 3+s -a1",
            "Фейк с малым TTL: DPI его видит, сервер — нет",
            "Low-TTL fake: DPI sees it, the server does not",
        ),
        Strategy(
            "fake_ttl4", "FAKE TTL 4",
            "-d1 -f-1 -t4 -n $SNI -Qr -r1+s -a1",
            "То же с TTL 4 — если DPI стоит близко к абоненту",
            "Same with TTL 4 — when DPI sits close to the subscriber",
        ),
        Strategy(
            "flowseal_fake", "FLOWSEAL FAKE",
            "-d1 -f-1 -t6 -l @fake/tls_clienthello_www_google_com.bin -r1+s -a1",
            "Как ALT10 у Flowseal: настоящий ClientHello google.com в роли фейка",
            "Like Flowseal ALT10: a real google.com ClientHello as the fake",
        ),
        Strategy(
            "flowseal_multisplit", "FLOWSEAL SPLIT",
            "-f1 -t5 -l @fake/tls_clienthello_www_google_com.bin -s1 -s0+s -d3+s -r1+s -a1",
            "Как general у Flowseal: фейк google.com в начале и разрезы по SNI",
            "Like Flowseal general: a google.com fake first, then cuts at the SNI",
        ),
        Strategy(
            "flowseal_hostfake", "FLOWSEAL HOSTFAKE",
            "-s0+s -f0+se -n $SNI -t5 -r1+s -a1",
            "Аналог hostfakesplit: имя сайта уходит отдельно, перед ним — фейковое",
            "hostfakesplit analogue: the host name goes alone, preceded by a fake one",
        ),
        Strategy(
            "fake_vk", "FAKE VK",
            "-d1 -f-1 -t6 -l @fake/tls_clienthello_vk_com.bin -s1+s -r1+s -a1",
            "Фейковый ClientHello vk.com — российский сайт, который DPI пропускает",
            "Fake vk.com ClientHello — a Russian site DPI lets through",
        ),
        Strategy(
            "fake_gosuslugi", "FAKE GOSUSLUGI",
            "-d1 -f-1 -t5 -l @fake/tls_clienthello_gosuslugi_ru.bin -s0+sm -r1+s -a1",
            "Фейковый ClientHello gosuslugi.ru и разрез посередине имени",
            "Fake gosuslugi.ru ClientHello and a cut mid-name",
        ),
        Strategy(
            "oob_timeout", "OOB TIMEOUT",
            "-o1 -d3 -s8 -s8+s -o30+s -r26+s -T5 -At -b 65536",
            "OOB и деление записи, увеличенный буфер и таймаут",
            "OOB plus record split, larger buffer and timeout",
        ),
        Strategy(
            "quic_fake", "QUIC FAKE",
            "-Ku -a6 -l @fake/quic_initial_www_google_com.bin -An -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -r1+s -a1",
            "Для режима «QUIC через прокси»: фейковый QUIC Initial google.com ×6",
            "For “QUIC through proxy”: fake google.com QUIC Initial ×6",
        ),
    )

    /** Набор ByeByeDPI: проверяется при автоподборе, в списке показываются только сработавшие. */
    val extended: List<Strategy> = ByeByeDpiPack.lines.mapIndexed { i, args ->
        val n = (i + 1).toString().padStart(2, '0')
        Strategy("bbd_$n", "BBD $n", args, "Из набора ByeByeDPI", "From the ByeByeDPI set")
    }

    val all: List<Strategy> get() = builtIn + extended

    val default: Strategy get() = builtIn.first()

    fun byId(id: String): Strategy? = all.firstOrNull { it.id == id }
}
