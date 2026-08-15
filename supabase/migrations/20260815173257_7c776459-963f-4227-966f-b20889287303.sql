ALTER TABLE public.settings
  ADD COLUMN IF NOT EXISTS email_subject_template text NOT NULL DEFAULT 'Invoice {{invoice_number}} from {{business_name}}',
  ADD COLUMN IF NOT EXISTS email_body_template text NOT NULL DEFAULT 'Hi {{client_name}},

Please find attached invoice {{invoice_number}} for recent garden work.

Total due: {{total}}{{due_clause}}.

Many thanks,
{{business_name}}';