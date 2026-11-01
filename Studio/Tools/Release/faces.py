# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# Reads which texels each face of a model shows, as the Hytale Blockbench plugin and Blockbench define it.

import numpy as np
FACES = ('front', 'back', 'left', 'right', 'top', 'bottom')
FACE_SIZE_AXES = {'front': (0, 1), 'back': (0, 1), 'right': (2, 1), 'left': (2, 1), 'top': (0, 2), 'bottom': (0, 2)}
_QUARTER = (2, 0, 3, 1)

def face_corners_on_atlas(fd, fw, fh):
    offset = fd.get('offset') or {}
    ox, oy = (int(offset.get('x', 0)), int(offset.get('y', 0)))
    angle = int(fd.get('angle', 0) or 0) % 360
    if angle % 90:
        raise ValueError(f'angle {angle} is not a quarter turn')
    mirror = fd.get('mirror') or {}
    sx = -1 if mirror.get('x') else 1
    sy = -1 if mirror.get('y') else 1
    if angle == 0:
        x1, y1, x2, y2 = (ox, oy, ox + fw * sx, oy + fh * sy)
    elif angle == 90:
        x1, y1, x2, y2 = (ox, oy + fw * sx, ox - fh * sy, oy)
    elif angle == 180:
        x1, y1, x2, y2 = (ox - fw * sx, oy - fh * sy, ox, oy)
    else:
        x1, y1, x2, y2 = (ox + fh * sy, oy, ox, oy - fw * sx)
    corners = [(x1, y1), (x2, y1), (x1, y2), (x2, y2)]
    for _ in range(angle // 90):
        corners = [corners[i] for i in _QUARTER]
    xs = [float(c[0]) for c in corners]
    ys = [float(c[1]) for c in corners]
    rect = (int(round(min(xs))), int(round(min(ys))), int(round(max(xs) - min(xs))), int(round(max(ys) - min(ys))))
    return (np.array(corners, float), rect)

def shapes(model):
    out = []

    def rec(n):
        sh = n.get('shape') or {}
        if sh.get('type') in ('box', 'quad'):
            out.append(sh)
        for c in n.get('children', []):
            rec(c)
    for n in model['nodes']:
        rec(n)
    return out

def face_dims(sh, face):
    size = (sh.get('settings') or {}).get('size') or {}
    s = [float(size.get(k, 0) or 0) for k in 'xyz']
    if sh['type'] == 'quad':
        ua, va = (0, 1)
    else:
        ua, va = FACE_SIZE_AXES[face]
    return (ua, va, max(1, round(s[ua])), max(1, round(s[va])), s)

def layout_faces(sh):
    layout = sh.get('textureLayout') or {}
    return [f for f in layout if (f in FACES if sh['type'] == 'box' else f == 'front')]

def sample(img, fd, fw, fh):
    corners, _ = face_corners_on_atlas(fd, fw, fh)
    c00, c10, c01 = (corners[0], corners[1], corners[2])
    H, W = img.shape[:2]
    jj, ii = np.meshgrid(np.arange(fh), np.arange(fw), indexing='ij')
    px = c00[0] + (ii + 0.5) / fw * (c10[0] - c00[0]) + (jj + 0.5) / fh * (c01[0] - c00[0])
    py = c00[1] + (ii + 0.5) / fw * (c10[1] - c00[1]) + (jj + 0.5) / fh * (c01[1] - c00[1])
    x, y = (np.floor(px).astype(int), np.floor(py).astype(int))
    if x.min() < 0 or y.min() < 0 or x.max() >= W or (y.max() >= H):
        return None
    return img[y, x]

def world_size(sh):
    size = (sh.get('settings') or {}).get('size') or {}
    stretch = sh.get('stretch') or {}
    return tuple((float(size.get(k, 0) or 0) * float(stretch.get(k, 1.0) or 1.0) for k in 'xyz'))

def without_texture_layout(model):
    import copy
    m = copy.deepcopy(model)
    for sh in shapes(m):
        sh.pop('textureLayout', None)
        sh.pop('stretch', None)
        (sh.get('settings') or {}).pop('size', None)
    return m

def compare_pair(old_model, old_img, new_model, new_img):
    if without_texture_layout(old_model) != without_texture_layout(new_model):
        return (0, 'the models differ outside their picture layout')
    a, b = (shapes(old_model), shapes(new_model))
    if len(a) != len(b):
        return (0, 'the models have a different number of shapes')
    n = 0
    for i, (sa, sb) in enumerate(zip(a, b)):
        if any((abs(x - y) > 1e-06 for x, y in zip(world_size(sa), world_size(sb)))):
            return (n, f'shape {i} changed its world size')
        fa, fb = (layout_faces(sa), layout_faces(sb))
        if sorted(fa) != sorted(fb):
            return (n, f'shape {i} draws different faces')
        for f in fa:
            _, _, w0, h0, _ = face_dims(sa, f)
            _, _, w1, h1, _ = face_dims(sb, f)
            want = sample(old_img, sa['textureLayout'][f], w0, h0)
            got = sample(new_img, sb['textureLayout'][f], w1, h1)
            if want is None:
                return (n, f'shape {i} face {f} reads off its OLD picture')
            if got is None:
                return (n, f'shape {i} face {f} reads off its new picture')
            if got.shape != want.shape:
                got = np.broadcast_to(got, want.shape)
            if not np.array_equal(got, want):
                return (n, f'shape {i} face {f} shows different texels')
            n += 1
    return (n, None)
