
from faster_whisper import WhisperModel

print("Loading Whisper model...")

# Load a small model that runs on CPU
model = WhisperModel(
    "base",
    device="cpu",
    compute_type="int8"
)

print("Whisper model loaded successfully!")

# Ask for an audio file
audio_path = input("Enter audio file path: ")

print("\nTranscribing audio...\n")

segments, info = model.transcribe(
    audio_path,
    beam_size=5
)

print("Detected language:", info.language)
print("\nRecognized Text:\n")

for segment in segments:
    print(segment.text)

print("\nTranscription completed!")
