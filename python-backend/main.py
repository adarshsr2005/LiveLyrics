
import os
import tempfile

from fastapi import FastAPI, UploadFile, File, HTTPException
from faster_whisper import WhisperModel

app = FastAPI(title="LiveLyrics API")

print("Loading local Whisper model...")

model = WhisperModel(
    "base",
    device="cpu",
    compute_type="int8"
)

print("Whisper model loaded successfully!")


@app.get("/")
def home():
    return {
        "message": "LiveLyrics Backend is running!",
        "transcription": "Local faster-whisper"
    }


@app.post("/transcribe")
async def transcribe_audio(audio: UploadFile = File(...)):

    suffix = os.path.splitext(audio.filename or "")[1] or ".wav"
    temp_path = None

    try:
        content = await audio.read()

        if not content:
            raise HTTPException(
                status_code=400,
                detail="Empty audio file"
            )

        with tempfile.NamedTemporaryFile(
            delete=False,
            suffix=suffix
        ) as temp:
            temp.write(content)
            temp_path = temp.name

        segments, info = model.transcribe(
            temp_path,
            beam_size=5
        )
        recognized_text = " ".join(
            segment.text.strip()
            for segment in segments
        )

        return {
            "recognized_text": recognized_text,
            "language": info.language
        }

    except HTTPException:
        raise

    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=str(e)
        )

    finally:
        if temp_path and os.path.exists(temp_path):
            os.remove(temp_path)
