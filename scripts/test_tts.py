import os
import wave
from pathlib import Path
from google import genai
from google.genai import types

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
client = genai.Client(api_key=GEMINI_API_KEY)

def save_pcm_as_wav(pcm_data, output_path, rate=24000, channels=1, sample_width=2):
    with wave.open(str(output_path), 'wb') as wav_file:
        wav_file.setnchannels(channels)
        wav_file.setsampwidth(sample_width)
        wav_file.setframerate(rate)
        wav_file.writeframes(pcm_data)

def test_model(model_name, text="This is a test of the Gemini audio system."):
    print(f"\n--- Testing model: {model_name} with text: '{text}' ---")
    config = types.GenerateContentConfig(
        response_modalities=["AUDIO"],
        speech_config=types.SpeechConfig(
            voice_config=types.VoiceConfig(
                prebuilt_voice_config=types.PrebuiltVoiceConfig(
                    voice_name="Puck"
                )
            )
        )
    )
    
    try:
        response = client.models.generate_content(
            model=model_name,
            contents=text,
            config=config
        )
        print("Response received successfully!")
        
        if not response.candidates:
            print("No candidates in response.")
            if response.prompt_feedback:
                print(f"Prompt Feedback: {response.prompt_feedback}")
            return
            
        candidate = response.candidates[0]
        print(f"Candidate Finish Reason: {candidate.finish_reason}")
        if candidate.safety_ratings:
            print("Safety Ratings:")
            for rating in candidate.safety_ratings:
                print(f"  {rating.category}: {rating.probability}")
                
        if not candidate.content or not candidate.content.parts:
            print("No content or parts found in candidate.")
            return
            
        for idx, part in enumerate(candidate.content.parts):
            print(f"Part {idx}:")
            print(f"  inline_data: {'Yes' if part.inline_data else 'No'}")
            if part.inline_data:
                mime_type = part.inline_data.mime_type
                print(f"  mime_type: {mime_type}")
                data_len = len(part.inline_data.data) if part.inline_data.data else 0
                print(f"  data length: {data_len} bytes")
                
                if data_len > 0:
                    filename = f"test_{model_name.replace('-','_').replace('.','_')}.wav"
                    if "audio/l16" in mime_type:
                        # Extract rate from mime_type if available, e.g., "audio/l16; rate=24000; channels=1"
                        rate = 24000
                        if "rate=" in mime_type:
                            parts = mime_type.split(";")
                            for p in parts:
                                if "rate=" in p:
                                    rate = int(p.split("=")[1].strip())
                        save_pcm_as_wav(part.inline_data.data, filename, rate=rate)
                        print(f"  Saved converted WAV to {filename}")
                    else:
                        ext = "mp3" if "mpeg" in mime_type or "mp3" in mime_type else "raw"
                        filename = f"test_{model_name.replace('-','_').replace('.','_')}.{ext}"
                        Path(filename).write_bytes(part.inline_data.data)
                        print(f"  Saved raw audio to {filename}")
            if part.text:
                print(f"  text: {part.text}")
    except Exception as e:
        print(f"Error testing model {model_name}: {e}")

if __name__ == "__main__":
    if not GEMINI_API_KEY:
        print("GEMINI_API_KEY not found in local.properties!")
    else:
        test_model("gemini-2.5-pro-preview-tts")
        test_model("gemini-3.1-flash-tts-preview")
