# SPDX-License-Identifier: GPL-3.0-or-later
"""Exports htdemucs and the htdemucs_ft vocals model to ONNX for the separation spike.

Run from a checkout of sevagh/demucs.onnx with its `demucs-for-onnx` package installed (see
docs/spikes/separation-spike.md). Differs from that repo's convert-pth-to-onnx.py in three ways:
- the fine-tuned vocals model is taken from the htdemucs_ft bag (index 3, weight row [0, 0, 0, 1]);
  there is no model named `htdemucs_ft_vocals`, and the original script would take index 0 (drums);
- the TorchScript exporter is forced (dynamo=False): newer torch defaults to the dynamo exporter;
- nn.MultiheadAttention's fused fast path is disabled: it has no ONNX export.
Each export is then checked against PyTorch on the same input with ONNX Runtime.

usage: convert_demucs.py <dest dir>   → <dest>/htdemucs.onnx, <dest>/htdemucs_ft_vocals.onnx
"""
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from demucs.htdemucs import HTDemucs, standalone_magnitude, standalone_spec
from demucs.pretrained import get_model

# output name → (pretrained bag, index in the bag)
TARGETS = {
    "htdemucs": ("htdemucs", 0),
    "htdemucs_ft_vocals": ("htdemucs_ft", 3),
}


def core(bag_name, index):
    bag = get_model(bag_name)
    if bag_name == "htdemucs_ft":
        assert list(bag.weights[index]) == [0.0, 0.0, 0.0, 1.0], bag.weights[index]
    model = bag.models[index]
    assert isinstance(model, HTDemucs), type(model)
    return model.eval()


def main(dest):
    torch.backends.mha.set_fastpath_enabled(False)
    dest.mkdir(parents=True, exist_ok=True)
    for name, (bag_name, index) in TARGETS.items():
        model = core(bag_name, index)
        print(f"{name}: sources {model.sources}, segment {model.segment}, rate {model.samplerate}")
        torch.manual_seed(0)
        wave = torch.randn(1, 2, int(model.segment * model.samplerate)) * 0.1
        spec = standalone_magnitude(standalone_spec(wave))
        path = dest / f"{name}.onnx"
        with torch.no_grad():
            torch.onnx.export(
                model, (wave, spec), path,
                export_params=True, opset_version=17, do_constant_folding=True,
                input_names=["input"], output_names=["output"], dynamo=False,
            )
            expected = [t.numpy() for t in model(wave, spec)]
        session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
        inputs = session.get_inputs()
        print("  inputs: ", [(i.name, i.shape) for i in inputs])
        print("  outputs:", [(o.name, o.shape) for o in session.get_outputs()])
        actual = session.run(None, {inputs[0].name: wave.numpy(), inputs[1].name: spec.numpy()})
        for e, a in zip(expected, actual):
            print(f"  output {e.shape}: max |torch - ort| = {np.max(np.abs(e - a)):.2e} (max |torch| = {np.max(np.abs(e)):.2e})")
        print(f"  wrote {path} ({path.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
