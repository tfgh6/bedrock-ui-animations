import os

WORK = r"D:\Program Files (x86)\deepseekHarness\Project"
MIXIN = os.path.join(WORK, "ui-transitions", "src", "com", "uitransitions", "mixin")

REPLACEMENTS = {
    "BlitRenderStateMixin.java": ("UiTransitions.applyAlpha(", "UiTransitions.applyAlphaBlit("),
    "TiledBlitRenderStateMixin.java": ("UiTransitions.applyAlpha(", "UiTransitions.applyAlphaBlit("),
    "ColoredRectangleRenderStateMixin.java": ("UiTransitions.applyAlpha(", "UiTransitions.applyAlphaBlit("),
    "GuiTextRenderStateMixin.java": ("UiTransitions.applyAlpha(", "UiTransitions.applyAlphaText("),
}

for name, (old, new) in REPLACEMENTS.items():
    path = os.path.join(MIXIN, name)
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    count = text.count(old)
    text = text.replace(old, new)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)
    print("%-40s 替换 %d 处" % (name, count))
