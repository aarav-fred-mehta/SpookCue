import os
import sys
import urllib.request
import tarfile
import shutil

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS_MODELS_DIR = os.path.join(BASE_DIR, "app", "src", "main", "assets", "models")
SENSE_VOICE_DIR = os.path.join(ASSETS_MODELS_DIR, "sense_voice")

SENSE_VOICE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2"

def download_file(url, dest_path):
    print(f"Downloading {url} -> {dest_path}...")
    os.makedirs(os.path.dirname(dest_path), exist_ok=True)
    urllib.request.urlretrieve(url, dest_path)
    print(f"Downloaded {dest_path} ({os.path.getsize(dest_path)} bytes)")

def main():
    os.makedirs(SENSE_VOICE_DIR, exist_ok=True)
    model_dest = os.path.join(SENSE_VOICE_DIR, "model.int8.onnx")
    tokens_dest = os.path.join(SENSE_VOICE_DIR, "tokens.txt")

    if os.path.exists(model_dest) and os.path.exists(tokens_dest):
        print("SenseVoice model and tokens already exist.")
        return

    tar_path = os.path.join(BASE_DIR, "models", "sense_voice.tar.bz2")
    if not os.path.exists(tar_path):
        download_file(SENSE_VOICE_URL, tar_path)

    print(f"Extracting {tar_path}...")
    with tarfile.open(tar_path, "r:bz2") as tar:
        for member in tar.getmembers():
            base = os.path.basename(member.name)
            if base == "model.int8.onnx":
                with tar.extractfile(member) as f_in, open(model_dest, "wb") as f_out:
                    shutil.copyfileobj(f_in, f_out)
                print(f"Extracted {model_dest} ({os.path.getsize(model_dest)} bytes)")
            elif base == "tokens.txt":
                with tar.extractfile(member) as f_in, open(tokens_dest, "wb") as f_out:
                    shutil.copyfileobj(f_in, f_out)
                print(f"Extracted {tokens_dest} ({os.path.getsize(tokens_dest)} bytes)")

    print("SenseVoice setup complete.")

if __name__ == "__main__":
    main()
