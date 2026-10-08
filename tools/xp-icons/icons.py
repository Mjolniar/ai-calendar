"""Icon definitions for the AI Calendar XP theme. All artwork is original; drawn on a 24x24 grid."""
import math
from iconlib import P, lin, rad, circle, rrect, _n

NAVY = "#2A4A8A"
GREEN_HI, GREEN_LO, GREEN_OUT = "#8BE07A", "#1E9A1E", "#136D13"
GOLD_OUT = "#7A5600"


def star(cx, cy, r_out, r_in, n, rot=-90.0):
    pts = []
    for i in range(n * 2):
        r = r_out if i % 2 == 0 else r_in
        a = math.radians(rot + i * 180.0 / n)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return "M" + " L".join(f"{_n(x)} {_n(y)}" for x, y in pts) + " Z"


def sparkle(cx, cy, r, k=0.12):
    """Four-point sparkle with concave sides."""
    c = r * k
    return (f"M{_n(cx)} {_n(cy - r)} C{_n(cx + c)} {_n(cy - r * 0.42)} {_n(cx + r * 0.42)} {_n(cy - c)} {_n(cx + r)} {_n(cy)} "
            f"C{_n(cx + r * 0.42)} {_n(cy + c)} {_n(cx + c)} {_n(cy + r * 0.42)} {_n(cx)} {_n(cy + r)} "
            f"C{_n(cx - c)} {_n(cy + r * 0.42)} {_n(cx - r * 0.42)} {_n(cy + c)} {_n(cx - r)} {_n(cy)} "
            f"C{_n(cx - r * 0.42)} {_n(cy - c)} {_n(cx - c)} {_n(cy - r * 0.42)} {_n(cx)} {_n(cy - r)} Z")


def icon(paths, size=24, dp=None):
    return {"w": size, "h": size, "dp": dp or size, "paths": paths}


WHITE_PAGE = lambda y0, y1: lin(0, y0, 0, y1, (0, "#FFFFFF"), (1, "#DCE6F6"))
BLUE_BAND = lambda y0, y1: lin(0, y0, 0, y1, (0, "#6AAEFF"), (1, "#1F5FD0"))
RED_BAND = lambda y0, y1: lin(0, y0, 0, y1, (0, "#FF8A66"), (1, "#D8321A"))
GREEN = lambda y0, y1: lin(0, y0, 0, y1, (0, GREEN_HI), (1, GREEN_LO))
YELLOW = lambda y0, y1: lin(0, y0, 0, y1, (0, "#FFF7A8"), (1, "#F5B400"))
RING = lambda x0, x1: lin(x0, 0, x1, 0, (0, "#F6F6F6"), (1, "#8A8A8A"))

CLOUD = ("M6.5 18.5 C3.6 18.5 2 16.6 2 14.4 C2 12.3 3.5 10.7 5.5 10.5 C5.9 7.5 8.4 5.2 11.4 5.2 "
         "C14.1 5.2 16.3 6.9 17.1 9.3 C19.9 9.4 22 11.4 22 14 C22 16.5 20 18.5 17.4 18.5 Z")


def cloud(badge):
    paths = [P(CLOUD, fill=lin(0, 5, 0, 18.5, (0, "#FFFFFF"), (1, "#BFD6F5")), stroke="#3E66A8", sw=1.1)]
    if badge == "ok":
        paths += [P(circle(17.6, 17.4, 4.4), fill=rad(16.4, 15.8, 5.4, (0, GREEN_HI), (1, GREEN_LO)), stroke="#FFFFFF", sw=1.1),
                  P("M15.5 17.5 L17.1 19.1 L19.8 15.9", stroke="#FFFFFF", sw=1.5)]
    elif badge == "off":
        paths += [P(circle(17.6, 17.4, 4.4), fill=rad(16.4, 15.8, 5.4, (0, "#FF9A80"), (1, "#CC2408")), stroke="#FFFFFF", sw=1.1),
                  P("M15.9 15.7 L19.3 19.1 M19.3 15.7 L15.9 19.1", stroke="#FFFFFF", sw=1.5)]
    elif badge == "warn":
        paths += [P("M17.6 12.4 L22.8 21.4 H12.4 Z", fill=YELLOW(12.4, 21.4), stroke="#FFFFFF", sw=2.0),
                  P("M17.6 12.4 L22.8 21.4 H12.4 Z", fill=YELLOW(12.4, 21.4), stroke=GOLD_OUT, sw=0.8),
                  P("M17.6 15.6 V18.3", stroke="#1A1A1A", sw=1.3),
                  P(circle(17.6, 19.9, 0.7), fill="#1A1A1A")]
    return icon(paths)


def cal_page(header, x=3.5, y=4.5, w=17, h=16.5, r=1.8, band=4.7):
    return [
        P(rrect(x, y, w, h, r), fill=WHITE_PAGE(y, y + h), stroke=NAVY, sw=1),
        P(f"M{_n(x)} {_n(y + band)} V{_n(y + r)} A{_n(r)} {_n(r)} 0 0 1 {_n(x + r)} {_n(y)} H{_n(x + w - r)} "
          f"A{_n(r)} {_n(r)} 0 0 1 {_n(x + w)} {_n(y + r)} V{_n(y + band)} Z",
          fill=header(y, y + band), stroke=("#8A1E0E" if header is RED_BAND else "#173F97"), sw=1),
        P(rrect(x + 3.4, y - 2.0, 2, 4.2, 1), fill=RING(x + 3.4, x + 5.4), stroke="#4A4A4A", sw=0.7),
        P(rrect(x + w - 5.4, y - 2.0, 2, 4.2, 1), fill=RING(x + w - 5.4, x + w - 3.4), stroke="#4A4A4A", sw=0.7),
    ]


ARCHIVE_BOX = [
    P(rrect(4, 9, 16, 11.5, 1), fill=lin(0, 9, 0, 20.5, (0, "#E9C993"), (1, "#B98A45")), stroke="#6E4A1A", sw=1),
    P(rrect(2.5, 4.5, 19, 5, 1), fill=lin(0, 4.5, 0, 9.5, (0, "#F6E2BB"), (1, "#D0A260")), stroke="#6E4A1A", sw=1),
    P(rrect(9.3, 12.2, 5.4, 2.2, 1.1), fill="#5A3A10"),
]

ICONS = {
    "App": icon(cal_page(RED_BAND) + [
        P(sparkle(12, 15.2, 4.7), fill=lin(0, 10.5, 0, 19.9, (0, "#FFE98A"), (1, "#F0A400")), stroke="#9A6A00", sw=0.7),
    ]),
    "New": icon(cal_page(BLUE_BAND, x=3, y=6, w=15.5, h=15.5, r=1.6, band=4.2) + [
        P(rrect(5.4, 12.2, 2.6, 2.4), fill="#A9BEE6"), P(rrect(9.4, 12.2, 2.6, 2.4), fill="#A9BEE6"),
        P(rrect(13.4, 12.2, 2.6, 2.4), fill="#A9BEE6"), P(rrect(5.4, 16.2, 2.6, 2.4), fill="#A9BEE6"),
        P(rrect(9.4, 16.2, 2.6, 2.4), fill="#A9BEE6"),
        P(star(18.3, 5.7, 5.2, 2.5, 8, rot=-90 + 11), fill="#FFFFFF", stroke="#FFFFFF", sw=1.6),
        P(star(18.3, 5.7, 5.2, 2.5, 8, rot=-90 + 11), fill=rad(17.5, 4.8, 5.6, (0, "#FFFBC8"), (1, "#FFB800")), stroke="#A87400", sw=0.7),
    ]),
    "ViewCalendar": icon(cal_page(BLUE_BAND, x=3, y=4.5, w=18, h=16.5, r=1.6, band=4.5) + [
        P(rrect(5.6, 11.2, 3.4, 3.0), fill="#A9BEE6"), P(rrect(10.3, 11.2, 3.4, 3.0), fill="#A9BEE6"),
        P(rrect(15.0, 11.2, 3.4, 3.0), fill=lin(0, 11.2, 0, 14.2, (0, "#FFB25C"), (1, "#EE7A12")), stroke="#9A4A00", sw=0.5),
        P(rrect(5.6, 15.6, 3.4, 3.0), fill="#A9BEE6"), P(rrect(10.3, 15.6, 3.4, 3.0), fill="#A9BEE6"),
        P(rrect(15.0, 15.6, 3.4, 3.0), fill="#A9BEE6"),
    ]),
    "ViewList": icon([
        P(rrect(3, 3, 18, 18, 1.6), fill=lin(0, 3, 0, 21, (0, "#FFFFFF"), (1, "#E6ECF6")), stroke="#4A6AA0", sw=1),
        P(rrect(5.4, 6, 3, 3, 0.6), fill=lin(0, 6, 0, 9, (0, "#6AAEFF"), (1, "#1F5FD0")), stroke="#173F97", sw=0.5),
        P(rrect(5.4, 10.5, 3, 3, 0.6), fill=GREEN(10.5, 13.5), stroke=GREEN_OUT, sw=0.5),
        P(rrect(5.4, 15, 3, 3, 0.6), fill=lin(0, 15, 0, 18, (0, "#FFB25C"), (1, "#EE7A12")), stroke="#9A4A00", sw=0.5),
        P(rrect(10.2, 6.6, 8.2, 1.8, 0.6), fill="#5F7396"),
        P(rrect(10.2, 11.1, 8.2, 1.8, 0.6), fill="#5F7396"),
        P(rrect(10.2, 15.6, 8.2, 1.8, 0.6), fill="#5F7396"),
    ]),
    "Find": icon([
        P("M15 15 L20.4 20.4", stroke="#3A2408", sw=4.4),
        P("M15 15 L20.4 20.4", stroke="#A8702E", sw=2.8),
        P("M15.3 14.6 L20.5 19.8", stroke="#E0B070", sw=0.8, sa=0.9),
        P(circle(10, 10, 6.4), fill=rad(8.2, 7.8, 8.2, (0, "#FFFFFF"), (0.55, "#D2E8FF"), (1, "#86B8F0")), stroke="#35455E", sw=1.9),
        P("M6.4 9.4 A3.9 3.9 0 0 1 9.3 6.4", stroke="#FFFFFF", sw=1.3, sa=0.95),
    ]),
    "Sync": icon([
        P("M4.756 10.059 A7.5 7.5 0 0 1 17.745 7.179", stroke=GREEN_OUT, sw=4.2),
        P("M19.244 13.941 A7.5 7.5 0 0 1 6.255 16.821", stroke=GREEN_OUT, sw=4.2),
        P("M20.3 10.4 L20.6 4.7 L14.9 9.3 Z", fill="#3DB53D", stroke=GREEN_OUT, sw=1.2),
        P("M3.7 13.6 L3.4 19.3 L9.1 14.7 Z", fill="#3DB53D", stroke=GREEN_OUT, sw=1.2),
        P("M4.756 10.059 A7.5 7.5 0 0 1 17.745 7.179", stroke="#4CC44C", sw=2.4),
        P("M19.244 13.941 A7.5 7.5 0 0 1 6.255 16.821", stroke="#35A835", sw=2.4),
    ]),
    # A closed loop of two tracks, each ending in an arrowhead: the reminder comes round again.
    "Repeat": icon([
        # White edge first, so the loop stays visible on the blue of a selected row.
        P("M4.4 13.6 V10.4 A3.4 3.4 0 0 1 7.8 7 H15.2", stroke="#FFFFFF", sw=7.0),
        P("M19.6 10.4 V13.6 A3.4 3.4 0 0 1 16.2 17 H8.8", stroke="#FFFFFF", sw=7.0),
        P("M14.6 3.2 L20.4 7 L14.6 10.8 Z", fill="#FFFFFF", stroke="#FFFFFF", sw=3.2),
        P("M9.4 13.2 L3.6 17 L9.4 20.8 Z", fill="#FFFFFF", stroke="#FFFFFF", sw=3.2),
        P("M4.4 13.6 V10.4 A3.4 3.4 0 0 1 7.8 7 H15.2", stroke="#173F97", sw=4.0),
        P("M19.6 10.4 V13.6 A3.4 3.4 0 0 1 16.2 17 H8.8", stroke="#173F97", sw=4.0),
        P("M14.6 3.2 L20.4 7 L14.6 10.8 Z", fill=lin(14.6, 3.2, 20.4, 10.8, (0, "#8CBFFF"), (1, "#1F5FD0")), stroke="#173F97", sw=1.1),
        P("M9.4 13.2 L3.6 17 L9.4 20.8 Z", fill=lin(9.4, 13.2, 3.6, 20.8, (0, "#8CBFFF"), (1, "#1F5FD0")), stroke="#173F97", sw=1.1),
        P("M4.4 13.6 V10.4 A3.4 3.4 0 0 1 7.8 7 H15.2", stroke="#5A9CF8", sw=2.2),
        P("M19.6 10.4 V13.6 A3.4 3.4 0 0 1 16.2 17 H8.8", stroke="#3F86EC", sw=2.2),
    ]),
    "Cloud": cloud(None),
    "CloudOk": cloud("ok"),
    "CloudOff": cloud("off"),
    "CloudWarn": cloud("warn"),
    "Import": icon([
        P("M2.5 7 A1 1 0 0 1 3.5 6 H8.6 L10.6 8 H20.5 A1 1 0 0 1 21.5 9 V19 A1 1 0 0 1 20.5 20 H3.5 A1 1 0 0 1 2.5 19 Z",
          fill=lin(0, 6, 0, 20, (0, "#FFE08A"), (1, "#E0A21C")), stroke="#8A6410", sw=1),
        P("M2.5 11.4 H21.5 V19 A1 1 0 0 1 20.5 20 H3.5 A1 1 0 0 1 2.5 19 Z",
          fill=lin(0, 11.4, 0, 20, (0, "#FFF4C8"), (1, "#F6C644")), stroke="#8A6410", sw=1),
        P("M15.4 1.6 H19.6 V6.2 H22.2 L17.5 11.4 L12.8 6.2 H15.4 Z", fill="#FFFFFF", stroke="#FFFFFF", sw=1.8),
        P("M15.4 1.6 H19.6 V6.2 H22.2 L17.5 11.4 L12.8 6.2 H15.4 Z", fill=GREEN(1.6, 11.4), stroke=GREEN_OUT, sw=0.9),
    ]),
    "School": icon([
        P("M6.2 11.2 V16 C6.2 17.9 8.8 19.3 12 19.3 C15.2 19.3 17.8 17.9 17.8 16 V11.2 L12 14 Z",
          fill=lin(0, 11, 0, 19.3, (0, "#8DA3D8"), (1, "#3F5590")), stroke="#1B2A52", sw=1),
        P("M12 3.8 L22.6 8.8 L12 13.8 L1.4 8.8 Z", fill=lin(0, 3.8, 0, 13.8, (0, "#C2D1F2"), (1, "#5571B5")), stroke="#1B2A52", sw=1),
        P("M12 5.6 L18.6 8.7", stroke="#FFFFFF", sw=0.9, sa=0.6),
        P("M20.4 9.8 V14.6", stroke="#D99A00", sw=1.3),
        P(circle(20.4, 15.9, 1.5), fill="#F5C400", stroke="#9A6A00", sw=0.7),
    ]),
    "Bell": icon([
        P(circle(12, 2.8, 1.1), fill="#FFCE2E", stroke=GOLD_OUT, sw=0.8),
        P("M9.8 18.4 A2.2 2.2 0 0 0 14.2 18.4 Z", fill="#C88A00", stroke=GOLD_OUT, sw=0.9),
        P("M12 3.4 C8.6 3.4 6.6 6 6.6 9.5 V13.6 L4.4 17.4 A0.6 0.6 0 0 0 4.9 18.4 H19.1 A0.6 0.6 0 0 0 19.6 17.4 L17.4 13.6 V9.5 C17.4 6 15.4 3.4 12 3.4 Z",
          fill=lin(6, 3, 18, 18, (0, "#FFF3AE"), (0.5, "#FFCE2E"), (1, "#D69600")), stroke=GOLD_OUT, sw=1),
        P("M8.5 9.6 C8.5 7.3 9.5 5.7 11 5.2", stroke="#FFFFFF", sw=1.1, sa=0.8),
    ]),
    "Check": icon([
        P("M3.6 12.7 L6.4 9.9 L9.7 13.2 L17.6 5 L20.4 7.8 L9.7 18.8 Z", fill=GREEN(5, 19), stroke=GREEN_OUT, sw=1),
    ]),
    "Delete": icon([
        P("M5.2 3.6 L12 10.4 L18.8 3.6 L20.9 5.7 L14.1 12.5 L20.9 19.3 L18.8 21.4 L12 14.6 L5.2 21.4 L3.1 19.3 L9.9 12.5 L3.1 5.7 Z",
          fill=lin(0, 3.6, 0, 21.4, (0, "#FF8E70"), (1, "#CC2408")), stroke="#7A1505", sw=1),
    ]),
    "Edit": icon([
        P("M9.092 18.02 L5.98 14.908 L14.111 6.777 L17.223 9.889 Z",
          fill=lin(10.044, 10.844, 13.156, 13.956, (0, "#FFEB7A"), (0.5, "#FFC81E"), (1, "#D69600")), stroke=GOLD_OUT, sw=0.9),
        P("M4 20 L9.092 18.02 L5.98 14.908 Z", fill="#F5DDB0", stroke=GOLD_OUT, sw=0.9),
        P("M4 20 L5.833 19.287 L4.713 18.167 Z", fill="#2A2A2A"),
        P("M17.223 9.889 L14.111 6.777 L15.525 5.363 L18.637 8.475 Z",
          fill=lin(15.2, 6.5, 17.6, 8.9, (0, "#F6F6F6"), (1, "#9A9A9A")), stroke="#4A4A4A", sw=0.8),
        P("M18.637 8.475 L15.525 5.363 L17.647 3.241 A2.2 2.2 0 0 1 20.759 6.353 Z",
          fill=lin(17.2, 4.2, 19.8, 6.8, (0, "#FFA8A8"), (1, "#D84040")), stroke="#7A1515", sw=0.8),
    ]),
    "Archive": icon(ARCHIVE_BOX),
    "Restore": icon(ARCHIVE_BOX + [
        P("M17.5 11.6 L22.6 17.2 H19.8 V22 H15.2 V17.2 H12.4 Z", fill="#FFFFFF", stroke="#FFFFFF", sw=1.8),
        P("M17.5 11.6 L22.6 17.2 H19.8 V22 H15.2 V17.2 H12.4 Z", fill=GREEN(11.6, 22), stroke=GREEN_OUT, sw=0.9),
    ]),
    "OpenLink": icon([
        P(rrect(3.2, 7.3, 13.5, 13.5, 1.6), fill=WHITE_PAGE(7.3, 20.8), stroke=NAVY, sw=1.2),
        P("M9.8 14.2 L17.4 6.6", stroke="#173F97", sw=3.6),
        P("M9.8 14.2 L17.4 6.6", stroke="#4A8FF5", sw=2),
        P("M12.6 3.2 H20.8 V11.4 Z", fill=lin(12.6, 3.2, 20.8, 11.4, (0, "#8CBFFF"), (1, "#1F5FD0")), stroke="#173F97", sw=0.9),
    ]),
    "Clock": icon([
        P(circle(12, 12, 9), fill=rad(10, 9, 11, (0, "#FFFFFF"), (1, "#D2E1F6")), stroke=NAVY, sw=1.6),
        P("M12 4.8 V6.2 M19.2 12 H17.8 M12 19.2 V17.8 M4.8 12 H6.2", stroke="#5A78A8", sw=1),
        P("M12 7.2 V12 L15.3 14", stroke="#1A1A1A", sw=1.6),
        P(circle(12, 12, 0.95), fill="#D8321A"),
    ]),
    "Warning": icon([
        P("M12 2.8 L22 20.4 H2 Z", fill=lin(0, 2.8, 0, 20.4, (0, "#FFF7A8"), (0.55, "#FFD83A"), (1, "#F0A800")), stroke="#7A5600", sw=1.3),
        P("M10.9 8.6 H13.1 L12.65 15 H11.35 Z", fill="#1A1A1A"),
        P(circle(12, 17.5, 1.15), fill="#1A1A1A"),
    ]),
    "Error": icon([
        P(circle(12, 12, 9.4), fill=rad(9, 8, 12, (0, "#FF9C84"), (0.6, "#DC2A0C"), (1, "#A81600")), stroke="#6E0E00", sw=1.1),
        P("M8.2 8.2 L15.8 15.8 M15.8 8.2 L8.2 15.8", stroke="#FFFFFF", sw=2.6),
    ]),
    "Sparkle": icon([
        P(sparkle(12, 12, 9.6, k=0.16), fill="#FFFFFF", stroke="#0E4A12", sw=1.0, sa=0.55),
        P(sparkle(18.6, 5.6, 3.2, k=0.16), fill="#FFFFFF", stroke="#0E4A12", sw=0.7, sa=0.55),
    ]),
    "Info": icon([
        P(circle(12, 12, 9.4), fill=rad(9, 8, 12, (0, "#9CC8FF"), (0.6, "#2B6DE0"), (1, "#1546B0")), stroke="#0D3A96", sw=1.1),
        P(circle(12, 7.5, 1.35), fill="#FFFFFF"),
        P(rrect(10.85, 10.2, 2.3, 7.4, 0.5), fill="#FFFFFF"),
    ]),
}

# ------------------------------------------------------------------ launcher artwork (108 x 108)
def launcher_foreground():
    # Kept inside the 66dp adaptive-icon safe zone (radius 33 around 54,54).
    x, y, w, h, r = 33.5, 34.5, 41.0, 43.0, 4.5
    band = 12.0
    grid = "#C3D2EC"
    paths = [
        # soft contact shadow
        P("M32.5 80.5 A21.5 3.6 0 1 0 75.5 80.5 A21.5 3.6 0 1 0 32.5 80.5 Z",
          fill=rad(54, 80.5, 21.5, (0, "#000000", 0.36), (1, "#000000", 0.0))),
        P(rrect(x, y, w, h, r), fill=lin(0, y, 0, y + h, (0, "#FFFFFF"), (1, "#D6E1F4")), stroke="#1E3F86", sw=1.5),
        # calendar grid
        P("M35 54.25 H73 M35 62 H73 M35 69.75 H73 M43.75 47.5 V76.5 M54 47.5 V76.5 M64.25 47.5 V76.5", stroke=grid, sw=0.9),
        # header band
        P(f"M{_n(x)} {_n(y + band)} V{_n(y + r)} A{_n(r)} {_n(r)} 0 0 1 {_n(x + r)} {_n(y)} H{_n(x + w - r)} "
          f"A{_n(r)} {_n(r)} 0 0 1 {_n(x + w)} {_n(y + r)} V{_n(y + band)} Z",
          fill=lin(0, y, 0, y + band, (0, "#FF9272"), (0.5, "#EE4A2A"), (1, "#C92A14")), stroke="#7E1A0C", sw=1.5),
        # gloss on the band
        P(f"M{_n(x + 1.8)} {_n(y + 5.6)} V{_n(y + 4.8)} A3 3 0 0 1 {_n(x + 4.8)} {_n(y + 1.8)} H{_n(x + w - 4.8)} "
          f"A3 3 0 0 1 {_n(x + w - 1.8)} {_n(y + 4.8)} V{_n(y + 5.6)} Z", fill="#FFFFFF", fa=0.28),
        # binder rings
        P(rrect(41.6, 29.5, 4.0, 10.5, 2.0), fill=lin(41.6, 0, 45.6, 0, (0, "#FAFAFA"), (0.5, "#C8C8C8"), (1, "#7C7C7C")), stroke="#3E3E3E", sw=1.0),
        P(rrect(62.4, 29.5, 4.0, 10.5, 2.0), fill=lin(62.4, 0, 66.4, 0, (0, "#FAFAFA"), (0.5, "#C8C8C8"), (1, "#7C7C7C")), stroke="#3E3E3E", sw=1.0),
        # sparkle
        P(sparkle(54, 62, 11.5), fill=lin(0, 50.5, 0, 73.5, (0, "#FFF0A0"), (0.5, "#FFC928"), (1, "#EE9C00")), stroke="#8E6000", sw=1.2),
        P(sparkle(51.1, 58.9, 2.9), fill="#FFFFFF", fa=0.85),
    ]
    return {"w": 108, "h": 108, "dp": 108, "paths": paths}


def launcher_background():
    return {"w": 108, "h": 108, "dp": 108, "paths": [
        P("M0 0 H108 V108 H0 Z", fill=lin(0, 0, 0, 108, (0, "#5AA6FF"), (0.45, "#1F6BEA"), (1, "#0A3FB8"))),
        P("M0 0 H108 V108 H0 Z", fill=rad(30, 22, 74, (0, "#FFFFFF", 0.34), (1, "#FFFFFF", 0.0))),
        P("M0 86 C30 76 78 76 108 86 V108 H0 Z", fill="#06308E", fa=0.35),
    ]}


def launcher_monochrome():
    return {"w": 108, "h": 108, "dp": 108, "paths": [
        P("M39 36 H69 Q74.5 36 74.5 41.5 V72 Q74.5 77.5 69 77.5 H39 Q33.5 77.5 33.5 72 V41.5 Q33.5 36 39 36 Z "
          "M33.5 46.5 H74.5 M43.6 30.5 V39.5 M64.4 30.5 V39.5", stroke="#FFFFFF", sw=4.2),
        P(sparkle(54, 62.2, 10.2), fill="#FFFFFF"),
    ]}


def notification_small():
    # Status-bar glyph: calendar page with a sparkle cut-out (alpha only, tinted by the system).
    return {"w": 24, "h": 24, "dp": 24, "paths": [
        P("M7 2 H9 V4 H15 V2 H17 V4 H19 A2 2 0 0 1 21 6 V19 A2 2 0 0 1 19 21 H5 A2 2 0 0 1 3 19 V6 A2 2 0 0 1 5 4 H7 Z "
          "M5 9 V19 H19 V9 Z", fill="#FFFFFF", rule="evenodd"),
        P(sparkle(12, 14, 4.2), fill="#FFFFFF"),
    ]}


def desktop_app():
    # The launcher artwork without its adaptive-icon margin: a 60 x 60 window around the calendar.
    art = launcher_foreground()
    return {"w": 60, "h": 60, "dp": 60, "vx": 24, "vy": 27, "paths": art["paths"]}


ART = {
    "desktop_app": desktop_app(),
    "launcher_fg": launcher_foreground(),
    "launcher_bg": launcher_background(),
    "launcher_mono": launcher_monochrome(),
    "notification_small": notification_small(),
}
