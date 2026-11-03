import os
import sys
import urllib.request
import tarfile
import shutil

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELS_DIR = os.path.join(BASE_DIR, "models")
ASSETS_MODELS_DIR = os.path.join(BASE_DIR, "app", "src", "main", "assets", "models")
MOONSHINE_ASSETS_DIR = os.path.join(ASSETS_MODELS_DIR, "moonshine")

SILERO_VAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
MOONSHINE_TAR_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2"

def download_file(url, dest_path):
    print(f"Downloading {url} -> {dest_path}...")
    os.makedirs(os.path.dirname(dest_path), exist_ok=True)
    urllib.request.urlretrieve(url, dest_path)
    print(f"Downloaded {dest_path} ({os.path.getsize(dest_path)} bytes)")

def main():
    os.makedirs(MODELS_DIR, exist_ok=True)
    os.makedirs(ASSETS_MODELS_DIR, exist_ok=True)
    os.makedirs(MOONSHINE_ASSETS_DIR, exist_ok=True)

    # 1. Download Silero VAD
    vad_dest = os.path.join(ASSETS_MODELS_DIR, "silero_vad.onnx")
    if not os.path.exists(vad_dest):
        download_file(SILERO_VAD_URL, vad_dest)
        shutil.copyfile(vad_dest, os.path.join(MODELS_DIR, "silero_vad.onnx"))
    else:
        print(f"Silero VAD already exists at {vad_dest}")

    # 2. Download and unpack Moonshine-Tiny
    expected_moonshine_files = {
        "preprocess.onnx": "preprocess.onnx",
        "encode.int8.onnx": "encode.onnx",
        "uncached_decode.int8.onnx": "uncached_decode.onnx",
        "cached_decode.int8.onnx": "cached_decode.onnx",
        "tokens.txt": "tokens.txt"
    }
    tar_dest = os.path.join(MODELS_DIR, "sherpa-onnx-moonshine-tiny-en-int8.tar.bz2")
    if not os.path.exists(tar_dest):
        download_file(MOONSHINE_TAR_URL, tar_dest)
    
    print(f"Extracting {tar_dest}...")
    with tarfile.open(tar_dest, "r:bz2") as tar:
        for member in tar.getmembers():
            base = os.path.basename(member.name)
            if base in expected_moonshine_files:
                target_name = expected_moonshine_files[base]
                member_file = tar.extractfile(member)
                if member_file:
                    dest_asset_path = os.path.join(MOONSHINE_ASSETS_DIR, target_name)
                    with open(dest_asset_path, "wb") as f_out:
                        shutil.copyfileobj(member_file, f_out)
                    print(f"Extracted to {dest_asset_path} ({os.path.getsize(dest_asset_path)} bytes)")
                    dest_repo_path = os.path.join(MODELS_DIR, target_name)
                    shutil.copyfile(dest_asset_path, dest_repo_path)
    
    print("Moonshine files extracted successfully.")

if __name__ == "__main__":
    main()
