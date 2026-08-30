import { useEffect, useState } from 'react';
import { useOperatorSettings, useUpdateOperatorBranding } from '../../api/queries.js';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { themeVars } from '../../lib/color.js';

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const HEX = /^#[0-9a-fA-F]{6}$/;
const str = (v) => (v == null ? '' : String(v));

/**
 * "Branding" tab of the operator config hub (SettingsLayout) - GET/PATCH
 * /api/operator/branding. Its own endpoint, not the full-replace
 * /api/fleet/settings (see OperatorBrandingController). Empty field -> null
 * on save = revert to the Bustix default. Colours theme the staff
 * workspace (via BrandingProvider) and single-booking ticket/tracking
 * cards.
 */
export default function OperatorBranding() {
  // Read the RAW override row (nullable fields) from the settings endpoint,
  // not the resolved GET /api/operator/branding (whose displayName is
  // filled with the legal name) - so a blank field here really means unset.
  const { data, isLoading, isError, error, refetch } = useOperatorSettings();
  const updateBranding = useUpdateOperatorBranding();
  const overrides = data?.overrides;

  const [form, setForm] = useState({ displayName: '', tagline: '', logoUrl: '', brandColor: '', accentColor: '' });
  const [formError, setFormError] = useState(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (data) {
      setForm({
        displayName: str(overrides?.displayName),
        tagline: str(overrides?.tagline),
        logoUrl: str(overrides?.logoUrl),
        brandColor: str(overrides?.brandColor),
        accentColor: str(overrides?.accentColor),
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data]);

  const set = (k) => (e) => {
    setForm((f) => ({ ...f, [k]: e.target.value }));
    setSaved(false);
  };

  async function handleSave(event) {
    event.preventDefault();
    setFormError(null);
    setSaved(false);
    for (const [k, label] of [['brandColor', 'Brand colour'], ['accentColor', 'Accent colour']]) {
      if (form[k].trim() && !HEX.test(form[k].trim())) {
        setFormError(`${label} must be a hex value like #1D4ED8.`);
        return;
      }
    }
    if (form.logoUrl.trim() && !/^https?:\/\/.+/.test(form.logoUrl.trim())) {
      setFormError('Logo URL must start with http:// or https://.');
      return;
    }
    try {
      await updateBranding.mutateAsync({
        displayName: form.displayName.trim() || null,
        tagline: form.tagline.trim() || null,
        logoUrl: form.logoUrl.trim() || null,
        brandColor: form.brandColor.trim() || null,
        accentColor: form.accentColor.trim() || null,
      });
      setSaved(true);
    } catch (err) {
      setFormError(err.message || 'Could not save branding.');
    }
  }

  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (isError) return <ErrorBanner message={error?.message} onRetry={refetch} />;

  const previewBrand = HEX.test(form.brandColor.trim()) ? form.brandColor.trim() : '#1D4ED8';
  const previewAccent = HEX.test(form.accentColor.trim()) ? form.accentColor.trim() : '#F59E0B';

  return (
    <div className="max-w-2xl">
      <p className="mb-6 text-sm text-ink-muted">
        Your logo, colours and customer-facing name. Applied to the staff workspace, passenger tickets and
        tracking pages. Leave a field blank to use the Bustix default.
      </p>

      <form onSubmit={handleSave} className="flex flex-col gap-6">
        <Section title="Identity">
          <Field label="Display name" hint="Shown to customers instead of your legal name">
            <input value={form.displayName} onChange={set('displayName')} placeholder="Bustix" className={inputClass} />
          </Field>
          <Field label="Tagline">
            <input value={form.tagline} onChange={set('tagline')} className={inputClass} />
          </Field>
          <Field label="Logo URL" hint="A hosted image (http/https)">
            <input value={form.logoUrl} onChange={set('logoUrl')} placeholder="https://…/logo.png" className={inputClass} />
          </Field>
          {form.logoUrl.trim() && (
            <img
              src={form.logoUrl.trim()}
              alt="Logo preview"
              className="h-10 w-auto max-w-[10rem] rounded border border-slate-200 bg-white object-contain p-1"
            />
          )}
        </Section>

        <Section title="Colours">
          <ColorField label="Brand colour" value={form.brandColor} onChange={set('brandColor')} placeholder="#1D4ED8" />
          <ColorField label="Accent colour" value={form.accentColor} onChange={set('accentColor')} placeholder="#F59E0B" />
        </Section>

        <Section title="Preview">
          <div
            className="overflow-hidden rounded-xl border border-slate-200"
            style={{ ...themeVars(previewBrand, 'brand'), ...themeVars(previewAccent, 'accent') }}
          >
            <div className="flex items-center gap-2 bg-brand px-4 py-2.5 text-white">
              <img
                src={form.logoUrl.trim() || '/brand/bustix-mark.svg'}
                alt=""
                className="h-6 w-auto max-w-[7rem] object-contain"
              />
              <span className="text-sm font-semibold">{form.displayName.trim() || 'Bustix'}</span>
            </div>
            <div className="flex items-center justify-between p-4">
              <span className="text-sm text-ink-muted">Addis Ababa → Hawassa</span>
              <span className="rounded-lg bg-accent px-3 py-1.5 text-sm font-semibold text-white">Select seats</span>
            </div>
          </div>
        </Section>

        {formError && <ErrorBanner message={formError} />}

        <div className="flex items-center gap-3">
          <button type="submit" disabled={updateBranding.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
            {updateBranding.isPending ? 'Saving…' : 'Save branding'}
          </button>
          {saved && <span className="text-sm text-success">Saved. Reload to see the workspace theme.</span>}
        </div>
      </form>
    </div>
  );
}

function ColorField({ label, value, onChange, placeholder }) {
  const valid = HEX.test(value.trim());
  return (
    <Field label={label}>
      <div className="flex items-center gap-2">
        <input
          type="color"
          value={valid ? value.trim() : placeholder}
          onChange={onChange}
          className="h-9 w-12 shrink-0 cursor-pointer rounded border border-slate-300"
        />
        <input value={value} onChange={onChange} placeholder={placeholder} className={`${inputClass} font-mono`} />
      </div>
    </Field>
  );
}

function Section({ title, children }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-surface p-4">
      <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-ink-muted">{title}</h2>
      <div className="flex flex-col gap-3">{children}</div>
    </div>
  );
}

function Field({ label, hint, children }) {
  return (
    <label className="block text-left">
      <span className="mb-1 flex items-baseline justify-between gap-2">
        <span className="text-xs font-semibold uppercase tracking-wide text-ink-muted">{label}</span>
        {hint && <span className="text-xs font-normal normal-case text-ink-muted">{hint}</span>}
      </span>
      {children}
    </label>
  );
}
