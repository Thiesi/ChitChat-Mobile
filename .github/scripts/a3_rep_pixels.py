"""DIAGNOSTIC (never merged), #378 F1 proof: per recorded notice rep, each frame's pts, the scroll-0 pixel (38,131: the
conversation's avatar row, visible only while the swapped-in scroller draws at 0) and whether the sync strip (x 0..12,
y 300..340) shows, from a cropped raw decode (no frame files). Writes <video>.pixels.csv next to each video. Read-only on
its inputs; observation only. Usage: a3_rep_pixels.py <dir>."""
import csv, os, subprocess, sys

D = sys.argv[1]
X0, Y0, CW, CH = 0, 120, 48, 224  # the crop: x 0..47, y 120..343
for name in sorted(os.listdir(D)):
    if not name.endswith('.mp4'):
        continue
    v = os.path.join(D, name)
    try:
        pts = subprocess.run(['ffprobe', '-loglevel', 'error', '-select_streams', 'v', '-show_entries', 'frame=pts_time',
                              '-of', 'csv=p=0', v], capture_output=True, text=True, check=True).stdout.split()
        raw = subprocess.run(['ffmpeg', '-loglevel', 'error', '-i', v, '-vsync', '0', '-vf', 'crop=%d:%d:%d:%d' % (CW, CH, X0, Y0),
                              '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], capture_output=True, check=True).stdout
    except Exception as e:  # observation only: a failed decode is reported, nothing retried
        print('%s: decode failed: %s' % (name, e))
        continue
    size = CW * CH * 3
    with open(v + '.pixels.csv', 'w', newline='') as out:
        w = csv.writer(out)
        w.writerow(['frame', 'pts', 'r', 'g', 'b', 'strip'])
        for k in range(len(raw) // size):
            f = raw[k * size:(k + 1) * size]
            def px(x, y):
                o = ((y - Y0) * CW + (x - X0)) * 3
                return f[o], f[o + 1], f[o + 2]
            r, g, b = px(38, 131)
            strip = any((lambda p: p[0] > 150 and p[2] > 150 and p[1] < 110)(px(x, y)) for y in range(300, 341, 4) for x in range(0, 40, 3))
            w.writerow([k + 1, pts[k] if k < len(pts) else '', r, g, b, int(strip)])
    print('%s: %d frames' % (name, len(raw) // size))
