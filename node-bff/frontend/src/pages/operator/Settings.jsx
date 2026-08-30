import { useEffect, useState } from 'react';
import { useOperatorSettings, useUpdateOperatorSettings } from '../../api/queries.js';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/** '' <-> a stored override; keeps controlled inputs happy and lets "cleared" mean null on save. */
const str = (v) => (v === null || v === undefined ? '' : String(v));
const numOrNull = (v) => (v.trim() === '' ? null : Number(v));

/** overrides.vatRate is a fraction (0.15); the form edits it as a percent (15). */
function overridesToForm(o) {
  return {
    vatPercent: o?.vatRate === null || o?.vatRate === undefined ? '' : String(o.vatRate * 100),
    reportingBufferMinutes: str(o?.reportingBufferMinutes),
    rescheduleMinNoticeHours: str(o?.rescheduleMinNoticeHours),
    rescheduleFeeSelfService: str(o?.rescheduleFeeSelfService),
    rescheduleFeeCounter: str(o?.rescheduleFeeCounter),
    supportPhone: str(o?.supportPhone),
    supportEmail: str(o?.supportEmail),
    supportAddress: str(o?.supportAddress),
    websiteUrl: str(o?.websiteUrl),
    ticketFooterNote: str(o?.ticketFooterNote),
  };
}

/**
 * "General" tab of the operator config hub (see SettingsLayout) -
 * GET/PATCH /api/fleet/settings. A singleton per operator: one form, not a
 * list. Each business-value field left blank falls back to the platform
 * default shown beside it (PATCH is a full replace of the override set -
 * see OperatorSettingsController). Contact / ticket-footer fields are shown
 * on tickets and tracking pages.
 */
export default function OperatorSettings() {
  const { data, isLoading, isError, error, refetch } = useOperatorSettings();
  const updateSettings = useUpdateOperatorSettings();

  const [form, setForm] = useState(overridesToForm(null));
  const [notify, setNotify] = useState(true);
  const [formError, setFormError] = useState(null);
  const [saved, setSaved] = useState(false);

  // Lazy-init from the server once loaded, same one-shot pattern as the
  // other operator pages' edit forms.
  useEffect(() => {
    if (data) {
      setForm(overridesToForm(data.overrides));
      setNotify(data.effective?.rescheduleNotificationsEnabled ?? true);
    }
  }, [data]);

  const set = (k) => (e) => {
    setForm((f) => ({ ...f, [k]: e.target.value }));
    setSaved(false);
  };

  async function handleSave(event) {
    event.preventDefault();
    setFormError(null);
    setSaved(false);
    const vp = form.vatPercent.trim();
    try {
      await updateSettings.mutateAsync({
        vatRate: vp === '' ? null : Number(vp) / 100,
        reportingBufferMinutes: numOrNull(form.reportingBufferMinutes),
        rescheduleMinNoticeHours: numOrNull(form.rescheduleMinNoticeHours),
        rescheduleFeeSelfService: numOrNull(form.rescheduleFeeSelfService),
        rescheduleFeeCounter: numOrNull(form.rescheduleFeeCounter),
        rescheduleNotificationsEnabled: notify,
        supportPhone: form.supportPhone.trim() || null,
        supportEmail: form.supportEmail.trim() || null,
        supportAddress: form.supportAddress.trim() || null,
        websiteUrl: form.websiteUrl.trim() || null,
        ticketFooterNote: form.ticketFooterNote.trim() || null,
      });
      setSaved(true);
    } catch (err) {
      setFormError(err.message || 'Could not save settings.');
    }
  }

  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (isError) return <ErrorBanner message={error?.message} onRetry={refetch} />;

  const d = data.defaults;

  return (
    <div className="max-w-2xl">
      <p className="mb-6 text-sm text-ink-muted">
        Business rules and contact details for your operator. Leave a value blank to use the platform default shown beside it.
      </p>

      <form onSubmit={handleSave} className="flex flex-col gap-6">
        <Section title="Tax & fares">
          <Field label="VAT rate (%)" hint={`Default ${(d.vatRate * 100).toFixed(2)}%`}>
            <input type="number" step="0.01" min="0" max="100" value={form.vatPercent} onChange={set('vatPercent')} placeholder={(d.vatRate * 100).toFixed(2)} className={inputClass} />
          </Field>
        </Section>

        <Section title="Timing">
          <Field label="Reporting-time buffer (minutes before departure)" hint={`Default ${d.reportingBufferMinutes}`}>
            <input type="number" min="0" value={form.reportingBufferMinutes} onChange={set('reportingBufferMinutes')} placeholder={String(d.reportingBufferMinutes)} className={inputClass} />
          </Field>
          <Field label="Reschedule minimum notice (hours before departure)" hint={`Default ${d.rescheduleMinNoticeHours}`}>
            <input type="number" min="0" value={form.rescheduleMinNoticeHours} onChange={set('rescheduleMinNoticeHours')} placeholder={String(d.rescheduleMinNoticeHours)} className={inputClass} />
          </Field>
        </Section>

        <Section title="Reschedule fees (ETB)">
          <Field label="Self-service (customer)" hint={`Default ${d.rescheduleFeeSelfService}`}>
            <input type="number" step="0.01" min="0" value={form.rescheduleFeeSelfService} onChange={set('rescheduleFeeSelfService')} placeholder={String(d.rescheduleFeeSelfService)} className={inputClass} />
          </Field>
          <Field label="Counter (agent-assisted)" hint={`Default ${d.rescheduleFeeCounter}`}>
            <input type="number" step="0.01" min="0" value={form.rescheduleFeeCounter} onChange={set('rescheduleFeeCounter')} placeholder={String(d.rescheduleFeeCounter)} className={inputClass} />
          </Field>
        </Section>

        <Section title="Notifications">
          <label className="flex items-start gap-3 text-sm text-ink">
            <input type="checkbox" checked={notify} onChange={(e) => { setNotify(e.target.checked); setSaved(false); }} className="mt-0.5 h-4 w-4 rounded border-slate-300" />
            <span>
              Notify booked customers about reschedules and trip time changes
              <span className="block text-xs text-ink-muted">
                Governs the per-booking reschedule notice and the email sent to every booked passenger when a trip's departure or arrival time is edited.
              </span>
            </span>
          </label>
        </Section>

        <Section title="Contact & ticket info">
          <p className="text-xs text-ink-muted">Shown on passenger tickets and public tracking pages.</p>
          <Field label="Support phone" hint="e.g. +251911234567">
            <input value={form.supportPhone} onChange={set('supportPhone')} placeholder="+251911234567" className={inputClass} />
          </Field>
          <Field label="Support email">
            <input type="email" value={form.supportEmail} onChange={set('supportEmail')} className={inputClass} />
          </Field>
          <Field label="Address">
            <input value={form.supportAddress} onChange={set('supportAddress')} className={inputClass} />
          </Field>
          <Field label="Website">
            <input value={form.websiteUrl} onChange={set('websiteUrl')} placeholder="https://…" className={inputClass} />
          </Field>
          <Field label="Ticket footer note">
            <textarea value={form.ticketFooterNote} onChange={set('ticketFooterNote')} rows={2} className={inputClass} />
          </Field>
        </Section>

        {formError && <ErrorBanner message={formError} />}

        <div className="flex items-center gap-3">
          <button type="submit" disabled={updateSettings.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
            {updateSettings.isPending ? 'Saving…' : 'Save settings'}
          </button>
          {saved && <span className="text-sm text-success">Saved.</span>}
        </div>
      </form>
    </div>
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
