-- Public read bucket for the optional fp32 Kokoro voice.
--
-- The 326 MB model does not belong in the APK or in git. The phone downloads
-- it once from this bucket (see KokoroHqDownloader), and can resume a dropped
-- connection with an HTTP Range request.
--
-- Populate it with scripts/upload-kokoro-fp32.sh. Until then the app falls
-- back to the official sherpa-onnx archive.
insert into storage.buckets (id, name, public)
values ('voice-models', 'voice-models', true)
on conflict (id) do update set public = true;
