"""Пиксельный логотип Tube (зелёная труба, сетка 32×32).

Превью:   python -I pixel_logo.py                     (пишет pixel.html рядом)
Ресурсы:  python -I pixel_logo.py android <путь к app/src/main/res>
"""
import sys
from pathlib import Path

MAP = """
................................
................................
..........KKKKKKKKKKKK..........
........KKLHLLLLLLLLLLKK........
......KKLLLLKKKKKKKKLLLLKK......
.....KMLLLDDDDDDDDDDDDLLLMK.....
.....KGMLKKKKKKKKKKKKKKLMGK.....
.....KGMLLKKKKKKKKKKKKLLMGK.....
.....KGMLLLLKKKKKKKKLLLLMGK.....
......KGMLLLLLLLLLLLLLLMGK......
.......KKKKKKKKKKKKKKKKKK.......
.......KGGGMMMGGGGMGGGGDK.......
.......KGGMHLLMGGGLMGGGDK.......
.......KGGMHLLMGGGLMGGGDK.......
.......KGGMLLLMGGGLMGGGDK.......
.......KGGMLLLMGGGLMGGGDK.......
.......KGGMLLLMGGGLMGGGDK.......
........KGMLLLMGGGLMGGDK........
........KGMLLLMGGGLMGGDK........
........KGMLLLMGGGLMGGDK........
........KGMLLLMGGGLMGGDK........
........KGMLLLMGGGLMGGDK........
........KGMLLLMGGGLMGGDK........
........KGMLLLMGGGLMGGDK........
........KGMLLLMGGGLMGGDK........
........KGMLLLMGGGLMGGDK........
.........KMLLLMGGGLMGDK.........
.........KMLLLMGGGLMGDK.........
..........KLLLMGGGLMGK..........
...........KKKKKKKKKK...........
................................
................................
"""

COLORS = {
    "K": "#000000",  # контур и отверстие
    "L": "#33CC33",  # светлый зелёный
    "M": "#1AA61A",  # переход
    "G": "#008000",  # тёмный зелёный
    "D": "#005A00",  # тень
    "H": "#8CF07A",  # блик
}
ORDER = "KDGMLH"
BACKGROUND = "#2A2A2A"
# Монохромная иконка и значок уведомления — силуэт без контура: отверстие и линия под ободом остаются щелями.
SILHOUETTE = "DGMLH"


def rows():
    lines = MAP.strip("\n").splitlines()
    assert len(lines) == 32 and all(len(r) == 32 for r in lines), "карта должна быть 32×32"
    return lines


def fmt(v):
    return ("%.3f" % v).rstrip("0").rstrip(".")


def paths(cell, ox, oy, colors=ORDER):
    """Горизонтальные полосы одного цвета → path для каждого цвета."""
    out = {}
    for j, row in enumerate(rows()):
        i = 0
        while i < 32:
            c = row[i]
            if c not in colors:
                i += 1
                continue
            k = i
            while k < 32 and row[k] == c:
                k += 1
            h = cell + 0.04  # лёгкое перекрытие рядов — без щелей при сглаживании
            out.setdefault(c, []).append(f"M{fmt(ox + i * cell)},{fmt(oy + j * cell)}h{fmt((k - i) * cell)}v{fmt(h)}h-{fmt((k - i) * cell)}z")
            i = k
    return [(c, "".join(out[c])) for c in colors if c in out]


def centered(size, cell):
    """Смещение, при котором центр рисунка (ячейки 5–26 × 2–29) попадает в центр холста."""
    return size / 2 - 16 * cell, size / 2 - 16 * cell


if __name__ == "__main__" and len(sys.argv) == 1:
    ox, oy = centered(108, 1.85)
    body = "".join(f'<path fill="{COLORS[c]}" d="{d}"/>' for c, d in paths(1.85, ox, oy))
    svg = f'<svg viewBox="18 18 72 72" width="100%" height="100%" shape-rendering="crispEdges"><rect width="108" height="108" fill="{BACKGROUND}"/>{body}</svg>'
    html = f"""<!doctype html><html><head><meta charset="utf-8"><title>Tube logo</title><style>
body{{margin:0;padding:16px;background:#ddd;font-family:sans-serif;display:flex;gap:18px;flex-wrap:wrap;align-items:center}}
.mask{{width:200px;height:200px;border-radius:50%;overflow:hidden}}
.sq{{width:120px;height:120px;border-radius:28px;overflow:hidden}}
.small{{width:48px;height:48px;border-radius:50%;overflow:hidden}}
</style></head><body><div class="mask">{svg}</div><div class="sq">{svg}</div><div class="small">{svg}</div></body></html>"""
    (Path(__file__).parent / "pixel.html").write_text(html, encoding="utf-8")


def write_android(res_dir):
    res = Path(res_dir)
    (res / "drawable").mkdir(parents=True, exist_ok=True)
    ns = 'xmlns:android="http://schemas.android.com/apk/res/android"'

    def vec(w, vw, body):
        return (f'<?xml version="1.0" encoding="utf-8"?>\n<vector {ns}\n    android:width="{w}dp"\n    android:height="{w}dp"\n'
                f'    android:viewportWidth="{vw}"\n    android:viewportHeight="{vw}">\n{body}</vector>\n')

    def path(color, d):
        return f'    <path android:fillColor="{color}" android:pathData="{d}" />\n'

    # Адаптивная иконка: 108×108, безопасная зона — круг диаметром 66 в центре.
    cell = 1.85
    ox, oy = centered(108, cell)
    color = "".join(path("#FF" + COLORS[c][1:], d) for c, d in paths(cell, ox, oy))
    mono = "".join(path("#FF000000", d) for _, d in paths(cell, ox, oy, SILHOUETTE))
    (res / "drawable/ic_launcher_foreground.xml").write_text(vec(108, 108, color), encoding="utf-8")
    (res / "drawable/ic_launcher_monochrome.xml").write_text(vec(108, 108, mono), encoding="utf-8")

    # Логотип внутри приложения: только рисунок, без фона (32×32 ячейки → вьюпорт 32).
    mark = "".join(path("#FF" + COLORS[c][1:], d) for c, d in paths(1, 0, 0))
    (res / "drawable/tube_mark.xml").write_text(vec(96, 32, mark), encoding="utf-8")

    # Значок уведомления и плитки: белый силуэт, 24dp.
    stat = "".join(path("#FFFFFFFF", d) for _, d in paths(1, 0, 0, SILHOUETTE))
    (res / "drawable/ic_stat_tube.xml").write_text(vec(24, 32, stat), encoding="utf-8")


if __name__ == "__main__" and len(sys.argv) > 1 and sys.argv[1] == "android":
    write_android(sys.argv[2])
    print("written")
