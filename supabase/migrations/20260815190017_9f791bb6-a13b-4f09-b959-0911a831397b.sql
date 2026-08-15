CREATE POLICY "own job photo files" ON storage.objects FOR ALL TO authenticated
USING (bucket_id = 'job-photos' AND (storage.foldername(name))[1] = auth.uid()::text)
WITH CHECK (bucket_id = 'job-photos' AND (storage.foldername(name))[1] = auth.uid()::text);