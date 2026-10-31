"""Build the app's original, stylized scooter as a self-contained glTF 2.0 model."""
import json
import math
import struct
import random
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
data = bytearray()
doc = {"asset": {"version": "2.0", "generator": "Sentinel procedural model"},
       "scene": 0, "scenes": [{"nodes": []}], "nodes": [], "meshes": [],
       "materials": [], "bufferViews": [], "accessors": []}


def material(name, color, metallic=0.0, roughness=0.5, emission=None):
    mat = {"name": name, "pbrMetallicRoughness": {"baseColorFactor": color,
           "metallicFactor": metallic, "roughnessFactor": roughness}}
    if emission:
        mat["emissiveFactor"] = emission
    if color[3] < 1:
        mat.update(alphaMode="BLEND", doubleSided=True)
    doc["materials"].append(mat)
    return len(doc["materials"]) - 1


rubber = material("rubber", [0.022, 0.028, 0.032, 1], 0, 0.88)
body = material("graphite", [0.105, 0.13, 0.15, 1], 0.7, 0.3)
metal = material("brushed alloy", [0.42, 0.47, 0.51, 1], 0.9, 0.25)
lime = material("electric lime", [0.63, 0.95, 0.09, 1], 0.25, 0.32, [0.16, 0.25, 0.015])
glass = material("headlight", [0.025, 0.035, 0.045, 1], 0, 0.2, [0, 0, 0])
red = material("rear light", [0.65, 0.015, 0.035, 1], 0.1, 0.3, [0.6, 0.01, 0.015])


def accessor(values, width, component=5126):
    global data
    while len(data) % 4:
        data.append(0)
    flat = [v for row in values for v in row] if width > 1 else values
    offset = len(data)
    data.extend(struct.pack("<" + ("f" if component == 5126 else "H") * len(flat), *flat))
    doc["bufferViews"].append({"buffer": 0, "byteOffset": offset, "byteLength": len(data) - offset})
    a = {"bufferView": len(doc["bufferViews"]) - 1, "componentType": component,
         "count": len(values), "type": {1: "SCALAR", 2: "VEC2", 3: "VEC3", 4: "VEC4"}[width]}
    if width == 3:
        a.update(min=[min(v[i] for v in values) for i in range(3)],
                 max=[max(v[i] for v in values) for i in range(3)])
    doc["accessors"].append(a)
    return len(doc["accessors"]) - 1


def mesh(vertices, normals, indices):
    assert len(vertices) == len(normals) and max(indices) < len(vertices)
    return {"attributes": {"POSITION": accessor(vertices, 3), "NORMAL": accessor(normals, 3)},
            "indices": accessor(indices, 1, 5123)}


def box():
    vertices, normals, indices = [], [], []
    for normal, face in [((1,0,0), [(1,-1,-1),(1,1,-1),(1,1,1),(1,-1,1)]),
                         ((-1,0,0), [(-1,-1,1),(-1,1,1),(-1,1,-1),(-1,-1,-1)]),
                         ((0,1,0), [(-1,1,-1),(-1,1,1),(1,1,1),(1,1,-1)]),
                         ((0,-1,0), [(-1,-1,1),(-1,-1,-1),(1,-1,-1),(1,-1,1)]),
                         ((0,0,1), [(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]),
                         ((0,0,-1), [(1,-1,-1),(-1,-1,-1),(-1,1,-1),(1,1,-1)])]:
        start = len(vertices)
        vertices.extend(tuple(x / 2 for x in v) for v in face)
        normals.extend([normal] * 4)
        indices.extend(start + i for i in [0,1,2,0,2,3])
    return mesh(vertices, normals, indices)


def cylinder(n=40):
    v, normals, idx = [], [], []
    for i in range(n + 1):
        a = i * 2 * math.pi / n
        for z in [-0.5, 0.5]:
            v.append((math.cos(a), math.sin(a), z)); normals.append((math.cos(a), math.sin(a), 0))
    for i in range(n):
        k = i * 2; idx.extend([k,k+2,k+3,k,k+3,k+1])
    for z, sign in [(-0.5, -1), (0.5, 1)]:
        center = len(v); v.append((0,0,z)); normals.append((0,0,sign))
        for i in range(n + 1):
            a = i * 2 * math.pi / n
            v.append((math.cos(a),math.sin(a),z)); normals.append((0,0,sign))
        for i in range(n):
            tri = [center,center+i+1,center+i+2]
            idx.extend(tri if sign > 0 else tri[::-1])
    return mesh(v, normals, idx)


def torus(major=0.15, minor=0.045, n=56, m=16):
    v, normals, idx = [], [], []
    for i in range(n + 1):
        a = i * 2 * math.pi / n
        for j in range(m + 1):
            b = j * 2 * math.pi / m
            v.append(((major+minor*math.cos(b))*math.cos(a), (major+minor*math.cos(b))*math.sin(a), minor*math.sin(b)))
            normals.append((math.cos(b)*math.cos(a),math.cos(b)*math.sin(a),math.sin(b)))
    for i in range(n):
        for j in range(m):
            k = i*(m+1)+j; idx.extend([k,k+m+1,k+1,k+1,k+m+1,k+m+2])
    return mesh(v, normals, idx)


cube, tube, tire = box(), cylinder(), torus()
mesh_cache = {}


def group(name, pos=(0,0,0), parent=None):
    index = len(doc["nodes"])
    doc["nodes"].append({"name": name, "translation": list(pos), "children": []})
    (doc["scenes"][0]["nodes"] if parent is None else doc["nodes"][parent]["children"]).append(index)
    return index


body_group = group("scooter body", (-0.025,-0.8,0))
active_parent = body_group


def part(name, primitive, mat, pos=(0,0,0), scale=(1,1,1), rotation=None):
    key = (primitive["attributes"]["POSITION"], primitive["indices"], mat)
    if key not in mesh_cache:
        mesh_cache[key] = len(doc["meshes"])
        doc["meshes"].append({"primitives": [dict(primitive, material=mat)]})
    node = {"name": name, "mesh": mesh_cache[key], "translation": list(pos), "scale": list(scale)}
    if rotation:
        node["rotation"] = rotation
    doc["nodes"][active_parent]["children"].append(len(doc["nodes"]))
    doc["nodes"].append(node)
    return len(doc["nodes"]) - 1


def rod(name, a, b, radius, mat):
    delta = [b[i] - a[i] for i in range(3)]
    length = math.sqrt(sum(x*x for x in delta))
    direction = [x/length for x in delta]
    q = [-direction[1], direction[0], 0, 1 + direction[2]]
    norm = math.sqrt(sum(x*x for x in q))
    q = [x/norm for x in q] if norm else [1,0,0,0]
    part(name, tube, mat, tuple((a[i]+b[i])/2 for i in range(3)), (radius,radius,length), q)


part("deck", cube, body, (0,0.28,0), (1.2,0.13,0.32))
part("grip", cube, rubber, (0,0.35,0), (1.1,0.018,0.3))
for z in [-0.164,0.164]:
    part("lime deck rail", cube, lime, (0,0.29,z), (1.05,0.02,0.008))
for x in [-0.4,-0.3,-0.2,-0.1,0,0.1,0.2,0.3,0.4]:
    part("deck tread", cube, body, (x,0.363,0), (0.008,0.008,0.27))
for x in [-0.76,0.78]:
    active_parent = group("rear wheel" if x < 0 else "front wheel", (x,0.21,0), body_group)
    part("tire", tire, rubber, (0,0,0), (1,1,1.8))
    part("rim", tube, body, (0,0,0), (0.125,0.125,0.12))
    for z in [-0.068,0.068]:
        part("hub", tube, lime, (0,0,z), (0.048,0.048,0.014))
        part("brake disc", tube, metal, (0,0,z*1.12), (0.103,0.103,0.006))
        part("rim marker",cube,lime,(.095,.025,z*1.22),(.026,.009,.009))
        for angle in range(0,360,60):
            a=math.radians(angle)
            rod("spoke",(0.035*math.cos(a),0.035*math.sin(a),z*1.2),
                (0.11*math.cos(a+0.25),0.11*math.sin(a+0.25),z*1.2),0.009,body)
    for angle in range(0,360,15):
        a=math.radians(angle)
        part("tire tread",cube,body,(0.194*math.cos(a),0.194*math.sin(a),0),
             (0.008,0.016,0.09),[0,0,math.sin(a/2),math.cos(a/2)])
active_parent = body_group
for z in [-0.1,0.1]:
    rod("rear swing arm",(-0.46,0.28,z),(-0.76,0.21,z),0.026,body)
    rod("front fork",(0.68,0.51,z),(0.78,0.21,z),0.025,metal)
    rod("front suspension",(0.66,0.57,z),(0.73,0.36,z),0.039,body)
    rod("suspension accent",(0.69,0.47,z),(0.72,0.38,z),0.041,lime)
rod("neck",(0.5,0.28,0),(0.68,0.52,0),0.056,body)
rod("stem",(0.68,0.52,0),(0.42,1.53,0),0.037,body)
rod("stem lime inset",(0.688,0.59,0.032),(0.46,1.45,0.032),0.008,lime)
part("folding clamp",cube,metal,(0.66,0.56,0),(0.12,0.065,0.10))
rod("handlebar",(0.42,1.53,-0.38),(0.42,1.53,0.38),0.024,metal)
rod("left grip",(0.42,1.53,-0.39),(0.42,1.53,-0.23),0.037,rubber)
rod("right grip",(0.42,1.53,0.23),(0.42,1.53,0.39),0.037,rubber)
part("dashboard",cube,rubber,(0.43,1.55,0),(0.12,0.04,0.14))
part("display",cube,lime,(0.43,1.573,0),(0.08,0.005,0.09))
part("front light housing",cube,body,(0.49,1.39,0),(0.10,0.075,0.12))
part("headlight",cube,glass,(0.544,1.39,0),(0.008,0.045,0.085))
part("rear fender",cube,body,(-0.74,0.426,0),(0.35,0.028,0.145))
part("rear light",cube,red,(-0.918,0.42,0),(0.009,0.03,0.1))
rod("brake lever",(0.47,1.51,-0.22),(0.52,1.47,-0.34),0.012,body)
rod("cable",(0.47,1.50,-0.18),(0.64,0.55,-0.06),0.006,rubber)
rod("kickstand",(-0.22,0.23,0.13),(-0.30,0.035,0.24),0.012,metal)

# A small tiled asphalt texture stays entirely inside the GLB.
def png_chunk(kind, payload):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I",zlib.crc32(kind+payload)&0xffffffff)


rng = random.Random(42)
pixels = bytearray()
for y in range(128):
    pixels.append(0)
    for x in range(128):
        shade = rng.choice([39,41,43,45,47,50,54])
        pixels.extend((shade,shade+3,shade+5))
png = b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR",struct.pack(">IIBBBBB",128,128,8,2,0,0,0)) + png_chunk(b"IDAT",zlib.compress(pixels)) + png_chunk(b"IEND",b"")
while len(data)%4: data.append(0)
doc["bufferViews"].append({"buffer":0,"byteOffset":len(data),"byteLength":len(png)})
data.extend(png)
doc["images"] = [{"bufferView":len(doc["bufferViews"])-1,"mimeType":"image/png"}]
doc["samplers"] = [{"magFilter":9729,"minFilter":9987,"wrapS":10497,"wrapT":10497}]
doc["textures"] = [{"source":0,"sampler":0}]
asphalt = material("asphalt",[0.65,0.67,0.7,1],0,0.98)
doc["materials"][asphalt]["pbrMetallicRoughness"]["baseColorTexture"] = {"index":0}
doc["materials"][asphalt]["pbrMetallicRoughness"]["baseColorTexture"]["extensions"] = {"KHR_texture_transform":{"offset":[0,0]}}
doc["materials"][asphalt].update(alphaMode="BLEND",doubleSided=True)
paint = material("road paint",[0.55,0.61,0.65,1],0,0.85)
doc["materials"][paint].update(alphaMode="BLEND",doubleSided=True)


def fade(value, start, end):
    t = max(0,min(1,(abs(value)-start)/(end-start)))
    return 1-t*t*(3-2*t)


def road_alpha(x,z):
    return fade(x,.95,1.95)*fade(z,.62,.82)


def faded_road(xs,zs,texture=False):
    vertices, normals, colors, indices, uvs = [], [], [], [], []
    for x in xs:
        for z in zs:
            vertices.append((x,.014,z)); normals.append((0,1,0))
            colors.append((1,1,1,road_alpha(x,z))); uvs.append((x/.9,z))
    for i in range(len(xs)-1):
        for j in range(len(zs)-1):
            k=i*len(zs)+j
            indices.extend([k,k+1,k+len(zs)+1,k,k+len(zs)+1,k+len(zs)])
    primitive = mesh(vertices,normals,indices)
    primitive["attributes"]["COLOR_0"] = accessor(colors,4)
    if texture: primitive["attributes"]["TEXCOORD_0"] = accessor(uvs,2)
    assert all(colors[i][3] == 0 for i,p in enumerate(vertices) if abs(p[0]) >= 1.95 or abs(p[2]) >= .82)
    return primitive


xs = [-1.95+i*.1 for i in range(40)]
road = faded_road(xs,[-.82+i*(1.64/26) for i in range(27)],texture=True)
road_group = group("road",(-0.025,-0.8,0))
active_parent = road_group
part("asphalt road",road,asphalt)
for z in [-.66,.66]:
    part("road edge paint",faded_road(xs,[z-.012,z+.012]),paint,(0,.003,0))
for index in range(8):
    active_parent = group(f"road tile {index}",((index-3.5)*.9,0,0),road_group)
    dash_material = material(f"moving road paint {index}",[.55,.61,.65,.999],0,.85)
    for z in [-.34,.34]:
        part("road dash",cube,dash_material,(0,.017,z),(.36,.003,.018))

# Transparent nested shells and a soft pool make the headlight beam visible in air.
doc["extensionsUsed"] = ["KHR_materials_unlit","KHR_lights_punctual","KHR_texture_transform"]
active_parent = body_group
start = (0.548,1.39,0)
target = (1.85,.02,0)
delta = [target[i]-start[i] for i in range(3)]
length = math.sqrt(sum(v*v for v in delta))
direction = [v/length for v in delta]
right = (0,0,1)
up = (-direction[1],direction[0],0)
beam_mat = material("headlight mist",[.80,.91,1,.009],0,1)
doc["materials"][beam_mat]["extensions"] = {"KHR_materials_unlit":{}}
vertices, normals, colors, indices = [], [], [], []
for radius in [.36,.31,.26,.21,.16,.11,.06]:
    first = len(vertices)
    rings = [(0,0),(.08,.45),(.25,.9),(.5,.7),(.8,.35),(1,0)]
    for fraction,alpha in rings:
        distance = length*fraction
        r = .018+radius*fraction
        for i in range(33):
            angle = i*math.tau/32
            vertices.append(tuple(start[j]+direction[j]*distance+r*(right[j]*math.cos(angle)+up[j]*math.sin(angle)) for j in range(3)))
            normals.append(tuple(right[j]*math.cos(angle)+up[j]*math.sin(angle) for j in range(3)))
            colors.append((1,1,1,alpha))
    for ring in range(len(rings)-1):
        for i in range(32):
            k=first+ring*33+i
            indices.extend([k,k+1,k+33,k+1,k+34,k+33])
beam = mesh(vertices,normals,indices)
beam["attributes"]["COLOR_0"] = accessor(colors,4)
part("headlight beam 0",beam,beam_mat)

pool_mat = material("headlight pool",[.78,.90,1,.15],0,1)
doc["materials"][pool_mat]["extensions"] = {"KHR_materials_unlit":{}}
vertices, normals, colors, indices = [], [], [], []
for ring,alpha in [(0,1),(.45,.7),(1,0)]:
    for i in range(33):
        angle=i*math.tau/32
        x,z = 1.72+math.cos(angle)*.58*ring,math.sin(angle)*.42*ring
        vertices.append((x,.023,z))
        normals.append((0,1,0)); colors.append((1,1,1,alpha*road_alpha(x,z)))
for ring in range(2):
    for i in range(32):
        k=ring*33+i;indices.extend([k,k+33,k+1,k+1,k+33,k+34])
pool = mesh(vertices,normals,indices)
pool["attributes"]["COLOR_0"] = accessor(colors,4)
part("headlight pool",pool,pool_mat)

doc["extensions"] = {"KHR_lights_punctual":{"lights":[{"name":"headlight spot","type":"spot","color":[.8,.91,1],"intensity":0,"range":5,
    "spot":{"innerConeAngle":.15,"outerConeAngle":.29}}]}}
# Rotate glTF's -Z spotlight axis toward the road.
quaternion = [direction[1],-direction[0],0,1-direction[2]]
norm = math.sqrt(sum(v*v for v in quaternion))
light = {"name":"headlight spot","translation":[start[0]-.025,start[1]-.8,start[2]],"rotation":[v/norm for v in quaternion],"extensions":{"KHR_lights_punctual":{"light":0}}}
doc["scenes"][0]["nodes"].append(len(doc["nodes"]))
doc["nodes"].append(light)
doc["buffers"] = [{"byteLength": len(data)}]
js = json.dumps(doc, separators=(",", ":")).encode()
js += b" " * (-len(js) % 4)
data += b"\0" * (-len(data) % 4)
total = 12 + 8 + len(js) + 8 + len(data)
glb = struct.pack("<4sII", b"glTF", 2, total) + struct.pack("<I4s", len(js), b"JSON") + js + struct.pack("<I4s", len(data), b"BIN\0") + data
out = ROOT / "app/src/main/assets/models/sentinel.glb"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_bytes(glb)
assert len(glb) == total and len(doc["nodes"]) > 80
names = {node["name"] for node in doc["nodes"]}
assert {"front wheel","rear wheel","road","headlight spot","headlight pool"} <= names
assert len([name for name in names if name.startswith("road tile ")]) == 8
print(f"Created {out.name}: {len(doc['nodes'])} parts, {len(glb):,} bytes")
