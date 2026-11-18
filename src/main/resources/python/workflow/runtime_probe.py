"""Observed runtime identity for prepared local inference (no model is loaded)."""
import argparse
import importlib.metadata
import json
import platform
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", required=True, choices=["cpu", "cuda"])
    args = parser.parse_args()
    import torch
    if args.device == "cuda" and not torch.cuda.is_available():
        raise SystemExit("CUDA was explicitly selected but is unavailable; choose CPU explicitly")
    packages = sorted((d.metadata.get("Name", "unknown"), d.version) for d in importlib.metadata.distributions())
    print(json.dumps({"python_version": sys.version.split()[0], "platform": platform.system(),
                      "packages": packages, "actual_device": args.device,
                      "torch_version": str(torch.__version__),
                      "device_name": torch.cuda.get_device_name(0) if args.device == "cuda" else "CPU"}), flush=True)


if __name__ == "__main__":
    main()
