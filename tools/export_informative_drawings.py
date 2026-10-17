"""
Rebuilds the line drawing models in app/src/main/assets/models from the original
Informative Drawings weights (Chan, Durand, Isola, CVPR 2022, MIT License):
https://github.com/carolineec/informative-drawings

    pip install torch onnx onnxruntime numpy
    # model.pth and model2.pth from https://huggingface.co/spaces/carolineec/informativedrawings
    python tools/export_informative_drawings.py model.pth model2.pth

Weights are stored as fp16 and cast back to fp32 inside the graph: ONNX Runtime folds
the casts when it loads the model, so inference runs in fp32 at half the file size.
"""
import os
import sys

import numpy as np
import onnx
import onnxruntime as ort
import torch
import torch.nn as nn
from onnx import TensorProto, helper, numpy_helper

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "models")
norm_layer = nn.InstanceNorm2d


# Generator architecture from the Informative Drawings repository.
class ResidualBlock(nn.Module):
    def __init__(self, f):
        super().__init__()
        self.conv_block = nn.Sequential(
            nn.ReflectionPad2d(1), nn.Conv2d(f, f, 3), norm_layer(f), nn.ReLU(inplace=True),
            nn.ReflectionPad2d(1), nn.Conv2d(f, f, 3), norm_layer(f))

    def forward(self, x):
        return x + self.conv_block(x)


class Generator(nn.Module):
    def __init__(self, input_nc=3, output_nc=1, n_residual_blocks=3):
        super().__init__()
        self.model0 = nn.Sequential(nn.ReflectionPad2d(3), nn.Conv2d(input_nc, 64, 7), norm_layer(64), nn.ReLU(inplace=True))
        down, f = [], 64
        for _ in range(2):
            down += [nn.Conv2d(f, f * 2, 3, stride=2, padding=1), norm_layer(f * 2), nn.ReLU(inplace=True)]
            f *= 2
        self.model1 = nn.Sequential(*down)
        self.model2 = nn.Sequential(*[ResidualBlock(f) for _ in range(n_residual_blocks)])
        up = []
        for _ in range(2):
            up += [nn.ConvTranspose2d(f, f // 2, 3, stride=2, padding=1, output_padding=1), norm_layer(f // 2), nn.ReLU(inplace=True)]
            f //= 2
        self.model3 = nn.Sequential(*up)
        self.model4 = nn.Sequential(nn.ReflectionPad2d(3), nn.Conv2d(64, output_nc, 7), nn.Sigmoid())

    def forward(self, x):
        return self.model4(self.model3(self.model2(self.model1(self.model0(x)))))


def fp16_weights(model):
    graph = model.graph
    inits, casts = [], []
    for init in list(graph.initializer):
        arr = numpy_helper.to_array(init)
        if arr.dtype != np.float32 or arr.size < 16:
            inits.append(init)
            continue
        half = numpy_helper.from_array(arr.astype(np.float16), init.name + "_fp16")
        inits.append(half)
        casts.append(helper.make_node("Cast", [half.name], [init.name], to=TensorProto.FLOAT, name=init.name + "_cast"))
    del graph.initializer[:]
    graph.initializer.extend(inits)
    nodes = casts + list(graph.node)
    del graph.node[:]
    graph.node.extend(nodes)
    return model


def export(weights, name):
    gen = Generator()
    gen.load_state_dict(torch.load(weights, map_location="cpu"))
    gen.eval()
    tmp = os.path.join(OUT, name + ".fp32.onnx")
    torch.onnx.export(gen, torch.rand(1, 3, 480, 640), tmp, input_names=["image"], output_names=["drawing"],
                      opset_version=17, dynamic_axes={"image": {2: "h", 3: "w"}, "drawing": {2: "h", 3: "w"}}, dynamo=False)
    path = os.path.join(OUT, f"informative_drawings_{name}.onnx")
    onnx.save(fp16_weights(onnx.load(tmp)), path)
    os.remove(tmp)

    x = torch.rand(1, 3, 500, 376)
    with torch.no_grad():
        ref = gen(x).numpy()
    y = ort.InferenceSession(path, providers=["CPUExecutionProvider"]).run(None, {"image": x.numpy()})[0]
    print(f"{path}: {os.path.getsize(path) / 1e6:.1f} MB, max difference from PyTorch {np.abs(y - ref).max():.5f}")


if __name__ == "__main__":
    contour, sketch = sys.argv[1:3]
    export(contour, "contour")
    export(sketch, "sketch")
