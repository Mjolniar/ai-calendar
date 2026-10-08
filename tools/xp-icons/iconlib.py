"""Tiny multi-target vector icon toolkit: one spec -> SVG preview, Compose ImageVector, Android VectorDrawable."""


def lin(x1, y1, x2, y2, *stops):
    return {"type": "linear", "x1": x1, "y1": y1, "x2": x2, "y2": y2, "stops": [_stop(s) for s in stops]}


def rad(cx, cy, r, *stops):
    return {"type": "radial", "cx": cx, "cy": cy, "r": r, "stops": [_stop(s) for s in stops]}


def _stop(s):
    if len(s) == 2:
        return (float(s[0]), s[1], 1.0)
    return (float(s[0]), s[1], float(s[2]))


def P(d, fill=None, stroke=None, sw=1.0, fa=1.0, sa=1.0, rule="nonzero"):
    return {"d": " ".join(d.split()), "fill": fill, "stroke": stroke, "sw": sw, "fa": fa, "sa": sa, "rule": rule}


def circle(cx, cy, r):
    return f"M{_n(cx - r)} {_n(cy)} A{_n(r)} {_n(r)} 0 1 1 {_n(cx + r)} {_n(cy)} A{_n(r)} {_n(r)} 0 1 1 {_n(cx - r)} {_n(cy)} Z"


def rrect(x, y, w, h, r=0.0):
    if r <= 0:
        return f"M{_n(x)} {_n(y)} H{_n(x + w)} V{_n(y + h)} H{_n(x)} Z"
    return (f"M{_n(x + r)} {_n(y)} H{_n(x + w - r)} A{_n(r)} {_n(r)} 0 0 1 {_n(x + w)} {_n(y + r)} "
            f"V{_n(y + h - r)} A{_n(r)} {_n(r)} 0 0 1 {_n(x + w - r)} {_n(y + h)} "
            f"H{_n(x + r)} A{_n(r)} {_n(r)} 0 0 1 {_n(x)} {_n(y + h - r)} "
            f"V{_n(y + r)} A{_n(r)} {_n(r)} 0 0 1 {_n(x + r)} {_n(y)} Z")


def _n(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return s if s not in ("-0", "") else "0"


def _f(v):
    return _n(float(v)) + "f"


# ---------------------------------------------------------------- SVG
def to_svg(icon, size=None):
    w, h = icon["w"], icon["h"]
    size = size or (w, h)
    defs, body = [], []
    gid = [0]

    def paint(p):
        if p is None:
            return "none"
        if isinstance(p, str):
            return p
        gid[0] += 1
        ident = f"g{id(icon) % 100000}_{gid[0]}"
        stops = "".join(
            f'<stop offset="{_n(o)}" stop-color="{c}" stop-opacity="{_n(a)}"/>' for o, c, a in p["stops"])
        if p["type"] == "linear":
            defs.append(f'<linearGradient id="{ident}" gradientUnits="userSpaceOnUse" x1="{_n(p["x1"])}" '
                        f'y1="{_n(p["y1"])}" x2="{_n(p["x2"])}" y2="{_n(p["y2"])}">{stops}</linearGradient>')
        else:
            defs.append(f'<radialGradient id="{ident}" gradientUnits="userSpaceOnUse" cx="{_n(p["cx"])}" '
                        f'cy="{_n(p["cy"])}" r="{_n(p["r"])}">{stops}</radialGradient>')
        return f"url(#{ident})"

    for p in icon["paths"]:
        attrs = [f'd="{p["d"]}"', f'fill="{paint(p["fill"])}"']
        if p["fill"] is not None and p["fa"] != 1.0:
            attrs.append(f'fill-opacity="{_n(p["fa"])}"')
        if p["rule"] == "evenodd":
            attrs.append('fill-rule="evenodd"')
        if p["stroke"] is not None:
            attrs += [f'stroke="{paint(p["stroke"])}"', f'stroke-width="{_n(p["sw"])}"',
                      'stroke-linecap="round"', 'stroke-linejoin="round"']
            if p["sa"] != 1.0:
                attrs.append(f'stroke-opacity="{_n(p["sa"])}"')
        body.append("<path " + " ".join(attrs) + "/>")
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size[0]}" height="{size[1]}" '
            f'viewBox="0 0 {w} {h}"><defs>{"".join(defs)}</defs>{"".join(body)}</svg>')


# ---------------------------------------------------------------- Kotlin (Compose ImageVector)
def _kcolor(c, a=1.0):
    c = c.lstrip("#")
    return f"Color(0x{int(round(a * 255)):02X}{c.upper()})"


def _kbrush(p, alpha_in_color=True):
    if isinstance(p, str):
        return f"SolidColor({_kcolor(p)})"
    stops = ", ".join(f"{_f(o)} to {_kcolor(c, a)}" for o, c, a in p["stops"])
    if p["type"] == "linear":
        return (f"Brush.linearGradient({stops}, start = Offset({_f(p['x1'])}, {_f(p['y1'])}), "
                f"end = Offset({_f(p['x2'])}, {_f(p['y2'])}))")
    return f"Brush.radialGradient({stops}, center = Offset({_f(p['cx'])}, {_f(p['cy'])}), radius = {_f(p['r'])})"


def to_kotlin(name, icon):
    w, h = icon["w"], icon["h"]
    out = [f"    val {name}: ImageVector by lazy(LazyThreadSafetyMode.NONE) {{",
           f"        ImageVector.Builder(\"Xp{name}\", {_n(icon.get('dp', w))}.dp, {_n(icon.get('dp', h))}.dp, {_f(w)}, {_f(h)}).apply {{"]
    for p in icon["paths"]:
        args = [f'addPathNodes("{p["d"]}")']
        if p["rule"] == "evenodd":
            args.append("pathFillType = PathFillType.EvenOdd")
        if p["fill"] is not None:
            args.append(f"fill = {_kbrush(p['fill'])}")
            if p["fa"] != 1.0:
                args.append(f"fillAlpha = {_f(p['fa'])}")
        if p["stroke"] is not None:
            args.append(f"stroke = {_kbrush(p['stroke'])}")
            args.append(f"strokeLineWidth = {_f(p['sw'])}")
            args.append("strokeLineCap = StrokeCap.Round")
            args.append("strokeLineJoin = StrokeJoin.Round")
            if p["sa"] != 1.0:
                args.append(f"strokeAlpha = {_f(p['sa'])}")
        out.append("            addPath(" + ", ".join(args) + ")")
    out += ["        }.build()", "    }"]
    return "\n".join(out)


# ---------------------------------------------------------------- Android VectorDrawable XML
def _acolor(c, a=1.0):
    return f"#{int(round(a * 255)):02X}{c.lstrip('#').upper()}"


def _agradient(attr, p, indent):
    pad = " " * indent
    lines = [f'{pad}<aapt:attr name="android:{attr}">']
    if p["type"] == "linear":
        lines.append(f'{pad}    <gradient android:type="linear" android:startX="{_n(p["x1"])}" android:startY="{_n(p["y1"])}" '
                     f'android:endX="{_n(p["x2"])}" android:endY="{_n(p["y2"])}">')
    else:
        lines.append(f'{pad}    <gradient android:type="radial" android:centerX="{_n(p["cx"])}" android:centerY="{_n(p["cy"])}" '
                     f'android:gradientRadius="{_n(p["r"])}">')
    for o, c, a in p["stops"]:
        lines.append(f'{pad}        <item android:offset="{_n(o)}" android:color="{_acolor(c, a)}" />')
    lines += [f"{pad}    </gradient>", f"{pad}</aapt:attr>"]
    return lines


def to_vector_xml(icon, dp=None, header_comment=None):
    w, h = icon["w"], icon["h"]
    dpw = dp or icon.get("dp", w)
    dph = dpw * h / w
    out = ['<?xml version="1.0" encoding="utf-8"?>']
    if header_comment:
        out.append(f"<!-- {header_comment} -->")
    out += ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
            '    xmlns:aapt="http://schemas.android.com/aapt"',
            f'    android:width="{_n(dpw)}dp"', f'    android:height="{_n(dph)}dp"',
            f'    android:viewportWidth="{_n(w)}"', f'    android:viewportHeight="{_n(h)}">']
    for p in icon["paths"]:
        attrs = [f'android:pathData="{p["d"]}"']
        children = []
        if p["rule"] == "evenodd":
            attrs.append('android:fillType="evenOdd"')
        if isinstance(p["fill"], str):
            attrs.append(f'android:fillColor="{_acolor(p["fill"])}"')
        elif p["fill"] is not None:
            children += _agradient("fillColor", p["fill"], 8)
        if p["fill"] is not None and p["fa"] != 1.0:
            attrs.append(f'android:fillAlpha="{_n(p["fa"])}"')
        if p["stroke"] is not None:
            if isinstance(p["stroke"], str):
                attrs.append(f'android:strokeColor="{_acolor(p["stroke"])}"')
            else:
                children += _agradient("strokeColor", p["stroke"], 8)
            attrs += [f'android:strokeWidth="{_n(p["sw"])}"', 'android:strokeLineCap="round"',
                      'android:strokeLineJoin="round"']
            if p["sa"] != 1.0:
                attrs.append(f'android:strokeAlpha="{_n(p["sa"])}"')
        joined = "\n        ".join(attrs)
        if children:
            out.append(f"    <path\n        {joined}>")
            out += children
            out.append("    </path>")
        else:
            out.append(f"    <path\n        {joined} />")
    out.append("</vector>")
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------- WPF XAML (DrawingImage)
def _xcolor(c, a=1.0):
    return f"#{int(round(a * 255)):02X}{c.lstrip('#').upper()}"


def _xbrush(tag, p, alpha, indent):
    """Child element for a gradient brush, or None when the paint fits in an attribute."""
    if p is None or isinstance(p, str):
        return None
    pad = " " * indent
    if p["type"] == "linear":
        head = (f'<LinearGradientBrush MappingMode="Absolute" StartPoint="{_n(p["x1"])},{_n(p["y1"])}" '
                f'EndPoint="{_n(p["x2"])},{_n(p["y2"])}"')
        tail = "</LinearGradientBrush>"
    else:
        head = (f'<RadialGradientBrush MappingMode="Absolute" Center="{_n(p["cx"])},{_n(p["cy"])}" '
                f'GradientOrigin="{_n(p["cx"])},{_n(p["cy"])}" RadiusX="{_n(p["r"])}" RadiusY="{_n(p["r"])}"')
        tail = "</RadialGradientBrush>"
    lines = [f"{pad}<{tag}>", f"{pad}    {head}>"]
    for o, c, a in p["stops"]:
        lines.append(f'{pad}        <GradientStop Offset="{_n(o)}" Color="{_xcolor(c, a * alpha)}" />')
    lines += [f"{pad}    {tail}", f"{pad}</{tag}>"]
    return lines


def to_xaml(key, icon):
    """One DrawingImage resource. The drawing always spans the full grid, so every icon scales alike."""
    w, h = icon["w"], icon["h"]
    vx, vy = icon.get("vx", 0), icon.get("vy", 0)
    box = f"M0,0 H{_n(w)} V{_n(h)} H0 Z"
    out = [f'    <DrawingImage x:Key="{key}">', "        <DrawingImage.Drawing>",
           f'            <DrawingGroup ClipGeometry="{box}">',
           f'                <GeometryDrawing Brush="Transparent" Geometry="{box}" />']
    pad = 16
    if vx or vy:
        out.append(f'                <DrawingGroup Transform="1,0,0,1,{_n(-vx)},{_n(-vy)}">')
        pad = 20
    sp = " " * pad
    for p in icon["paths"]:
        rule = "F0" if p["rule"] == "evenodd" else "F1"
        attrs = [f'Geometry="{rule} {p["d"]}"']
        children = []
        if isinstance(p["fill"], str):
            attrs.append(f'Brush="{_xcolor(p["fill"], p["fa"])}"')
        elif p["fill"] is not None:
            children += _xbrush("GeometryDrawing.Brush", p["fill"], p["fa"], pad + 4)
        if p["stroke"] is not None:
            pen = f'Thickness="{_n(p["sw"])}" StartLineCap="Round" EndLineCap="Round" LineJoin="Round"'
            if isinstance(p["stroke"], str):
                children += [f"{sp}    <GeometryDrawing.Pen>",
                             f'{sp}        <Pen Brush="{_xcolor(p["stroke"], p["sa"])}" {pen} />',
                             f"{sp}    </GeometryDrawing.Pen>"]
            else:
                children += [f"{sp}    <GeometryDrawing.Pen>", f"{sp}        <Pen {pen}>"]
                children += _xbrush("Pen.Brush", p["stroke"], p["sa"], pad + 12)
                children += [f"{sp}        </Pen>", f"{sp}    </GeometryDrawing.Pen>"]
        if children:
            out.append(f"{sp}<GeometryDrawing {' '.join(attrs)}>")
            out += children
            out.append(f"{sp}</GeometryDrawing>")
        else:
            out.append(f"{sp}<GeometryDrawing {' '.join(attrs)} />")
    if vx or vy:
        out.append("                </DrawingGroup>")
    out += ["            </DrawingGroup>", "        </DrawingImage.Drawing>", "    </DrawingImage>"]
    return "\n".join(out)
