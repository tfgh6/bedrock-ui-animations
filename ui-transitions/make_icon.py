"""把源图做成模组图标：边缘泛洪去白底 -> RGBA -> 128x128，并在 jar 根目录也放一份。

去白底只从四边泛洪，画面内部的白色（槽位高光等）不受影响。
"""
from collections import deque
from PIL import Image

SRC = r"C:\Users\DesKtop01\.dsh\attachments\v1\objects\d0\d08c3037daa80226b0d82ff5332747cd4d862444edcb29dc557800130df21708"
DST = r"D:\Program Files (x86)\deepseekHarness\Project\ui-transitions\resources\assets\ui_transitions\icon.png"
DST_ROOT = r"D:\Program Files (x86)\deepseekHarness\Project\ui-transitions\resources\icon.png"

TOLERANCE = 16          # 与白色的差距小于它就当作背景
SIDE = 128
MARGIN = 0.05           # 裁剪后留 5% 边距

im = Image.open(SRC).convert("RGBA")
w, h = im.size
px = im.load()

# ---------- 1) 从四边泛洪，把连通的近白像素标记为背景 ----------
is_bg = bytearray(w * h)
queue = deque()
for x in range(w):
    for y in (0, h - 1):
        queue.append((x, y))
for y in range(h):
    for x in (0, w - 1):
        queue.append((x, y))

while queue:
    x, y = queue.popleft()
    idx = y * w + x
    if is_bg[idx]:
        continue
    r, g, b, _ = px[x, y]
    if r < 255 - TOLERANCE or g < 255 - TOLERANCE or b < 255 - TOLERANCE:
        continue
    is_bg[idx] = 1
    if x > 0:
        queue.append((x - 1, y))
    if x < w - 1:
        queue.append((x + 1, y))
    if y > 0:
        queue.append((x, y - 1))
    if y < h - 1:
        queue.append((x, y + 1))

for y in range(h):
    for x in range(w):
        if is_bg[y * w + x]:
            px[x, y] = (255, 255, 255, 0)

# ---------- 2) 裁掉透明外边 + 留边距 ----------
bbox = im.getbbox()
print("  内容范围:", bbox)
if bbox:
    side = max(bbox[2] - bbox[0], bbox[3] - bbox[1])
    pad = int(side * MARGIN)
    box = (max(0, bbox[0] - pad), max(0, bbox[1] - pad),
           min(w, bbox[2] + pad), min(h, bbox[3] + pad))
    im = im.crop(box)
    side = max(im.size)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(im, ((side - im.width) // 2, (side - im.height) // 2))
    im = canvas

out = im.resize((SIDE, SIDE), Image.LANCZOS)
# optimize=False：避免被转成调色板 PNG，保证就是 8bit RGBA
out.save(DST, "PNG", optimize=False)
out.save(DST_ROOT, "PNG", optimize=False)
print("  输出:", DST)
print("  输出:", DST_ROOT, "(jar 根目录，兼容只扫根目录的启动器)")
print("  模式:", out.mode, out.size)
