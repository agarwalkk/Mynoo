import http.server
import socketserver
import json
import os
import time
import wave
import hashlib
import urllib.parse
from pathlib import Path
from google import genai
from google.genai import types
import sys

# Configure UTF-8 encoding for standard console outputs on Windows
if hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass
if hasattr(sys.stderr, 'reconfigure'):
    try:
        sys.stderr.reconfigure(encoding='utf-8')
    except Exception:
        pass

# Define directory structure
BASE_DIR = Path(__file__).resolve().parent / "static"
AUDIO_DIR = BASE_DIR / "audio"
BASE_DIR.mkdir(parents=True, exist_ok=True)
AUDIO_DIR.mkdir(parents=True, exist_ok=True)

# Helper to read local properties
def _get_local_property(key: str, default: str = "") -> str:
    paths_to_check = [
        Path(".") / "local.properties",
        Path(__file__).resolve().parent.parent / "local.properties",
        Path(__file__).resolve().parent / "local.properties"
    ]
    for p in paths_to_check:
        if p.exists():
            try:
                for line in p.read_text(encoding="utf-8").splitlines():
                    line = line.strip()
                    if line and not line.startswith("#") and "=" in line:
                        k, v = [x.strip() for x in line.split("=", 1)]
                        if k == key:
                            v = v.strip("'\"")
                            v = v.replace("\\:", ":").replace("\\\\", "\\")
                            return v
            except Exception:
                pass
    return default

GEMINI_API_KEY = _get_local_property("GEMINI_API_KEY", "")
client = None

if GEMINI_API_KEY:
    client = genai.Client(api_key=GEMINI_API_KEY)
else:
    print("WARNING: GEMINI_API_KEY not found in local.properties. Calls will fail unless API key is set in environment.")

def save_pcm_as_wav(pcm_data, output_path, rate=24000, channels=1, sample_width=2):
    try:
        with wave.open(str(output_path), 'wb') as wav_file:
            wav_file.setnchannels(channels)
            wav_file.setsampwidth(sample_width)
            wav_file.setframerate(rate)
            wav_file.writeframes(pcm_data)
        return True
    except Exception as e:
        print(f"Error saving WAV: {e}")
        return False

def generate_tts_for_model(model_name, text, voice_name):
    """
    Calls Gemini API with streaming to measure TTFB and total duration.
    Saves audio to WAV file and returns timing statistics.
    """
    if not client:
        return {"error": "API client not initialized. Check GEMINI_API_KEY."}

    config = types.GenerateContentConfig(
        response_modalities=["AUDIO"],
        speech_config=types.SpeechConfig(
            voice_config=types.VoiceConfig(
                prebuilt_voice_config=types.PrebuiltVoiceConfig(
                    voice_name=voice_name
                )
            )
        )
    )

    # Clean text to generate a unique filename
    text_hash = hashlib.md5(f"{text}_{voice_name}".encode("utf-8")).hexdigest()[:10]
    safe_model_name = model_name.replace("-", "_").replace(".", "_")
    audio_filename = f"tts_{safe_model_name}_{text_hash}.wav"
    audio_filepath = AUDIO_DIR / audio_filename

    # If the file already exists, we can returncached data, but let's re-run to test latency fresh
    # However, we will overwrite the file.
    
    start_time = time.perf_counter()
    ttfb = None
    audio_bytes_list = []
    mime_type = "audio/l16" # default fallback
    
    try:
        response_stream = client.models.generate_content_stream(
            model=model_name,
            contents=text,
            config=config
        )
        
        for chunk in response_stream:
            if ttfb is None:
                ttfb = time.perf_counter() - start_time
                
            if chunk.candidates and chunk.candidates[0].content and chunk.candidates[0].content.parts:
                for part in chunk.candidates[0].content.parts:
                    if part.inline_data and part.inline_data.data:
                        audio_bytes_list.append(part.inline_data.data)
                        if part.inline_data.mime_type:
                            mime_type = part.inline_data.mime_type
                            
        total_duration = time.perf_counter() - start_time
        
        if not audio_bytes_list:
            # Let's check unary if stream returned nothing or had an issue
            raise ValueError("No audio content returned in stream.")
            
        all_audio_bytes = b"".join(audio_bytes_list)
        
        # Determine sample rate and details from mime type
        rate = 24000
        # Check mime_type case-insensitively
        mt_lower = mime_type.lower()
        if "rate=" in mt_lower:
            try:
                # e.g., audio/l16; rate=24000; channels=1
                # or audio/L16;codec=pcm;rate=24000
                parts = mt_lower.replace(";", " ").replace(",", " ").split()
                for p in parts:
                    if p.startswith("rate="):
                        rate = int(p.split("=")[1])
            except Exception as e:
                print(f"Error parsing sample rate: {e}")
                
        # Write to WAV
        success = save_pcm_as_wav(all_audio_bytes, audio_filepath, rate=rate)
        if not success:
            raise IOError("Failed to convert PCM data to WAV.")
            
        audio_duration = len(all_audio_bytes) / (2 * rate) # 16-bit is 2 bytes per sample, 1 channel
        
        return {
            "success": True,
            "model_name": model_name,
            "ttfb_ms": round(ttfb * 1000, 2) if ttfb else None,
            "total_duration_ms": round(total_duration * 1000, 2),
            "audio_duration_sec": round(audio_duration, 2),
            "audio_url": f"/audio/{audio_filename}",
            "file_size_bytes": len(all_audio_bytes) + 44, # PCM + WAV header (44 bytes)
            "chars_per_sec": round(len(text) / total_duration, 2),
            "mime_type": mime_type
        }
    except Exception as e:
        print(f"Error calling {model_name}: {e}")
        return {
            "success": False,
            "model_name": model_name,
            "error": str(e)
        }

class TTSCompareHTTPHandler(http.server.SimpleHTTPRequestHandler):
    def translate_path(self, path):
        # Route `/audio/` path prefix to scripts/static/audio/
        parsed_path = urllib.parse.urlparse(path).path
        normalized_path = os.path.normpath(urllib.parse.unquote(parsed_path))
        
        if normalized_path.startswith("/audio/"):
            rel_path = normalized_path[len("/audio/"):]
            return str(AUDIO_DIR / rel_path)
            
        # Standard routing relative to BASE_DIR (scripts/static)
        if normalized_path.startswith("/"):
            normalized_path = normalized_path[1:]
            
        return str(BASE_DIR / normalized_path)

    def do_OPTIONS(self):
        # Support CORS preflight
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_POST(self):
        if self.path == "/api/compare":
            content_length = int(self.headers.get('Content-Length', 0))
            post_data = self.rfile.read(content_length)
            
            try:
                params = json.loads(post_data.decode('utf-8'))
                text = params.get("text", "").strip()
                voice = params.get("voice", "Puck").strip()
                
                if not text:
                    self.send_error_json(400, "Text parameter is required.")
                    return
                    
                print(f"\n[API] Running comparison on prompt: '{text[:40]}...' (Voice: {voice})")
                
                # Run both models
                res_pro = generate_tts_for_model("gemini-2.5-pro-preview-tts", text, voice)
                res_flash = generate_tts_for_model("gemini-3.1-flash-tts-preview", text, voice)
                
                response_payload = {
                    "text": text,
                    "voice": voice,
                    "gemini_2_5_pro": res_pro,
                    "gemini_3_1_flash": res_flash
                }
                
                self.send_json_response(200, response_payload)
            except Exception as e:
                self.send_error_json(500, f"Internal Server Error: {str(e)}")
        else:
            self.send_error(404, "Not Found")

    def send_json_response(self, status_code, data):
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode('utf-8'))

    def send_error_json(self, status_code, message):
        self.send_json_response(status_code, {"error": message})

def run_server(port=8080):
    handler = TTSCompareHTTPHandler
    # Enable socket reuse
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("", port), handler) as httpd:
        print("=" * 60)
        print("Gemini TTS Latency Comparison Server running at:")
        print(f"   http://localhost:{port}")
        print("=" * 60)
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down server.")

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Run the Gemini TTS Latency comparison server.")
    parser.add_argument("--port", type=int, default=8080, help="Port to run the HTTP server on.")
    args = parser.parse_args()
    
    # Pre-verify client load
    if not GEMINI_API_KEY:
        print("CRITICAL: GEMINI_API_KEY environment variable or local.properties property not configured!")
    run_server(args.port)
