import { useState } from 'react';
import { useTrackWaybill } from '../api/queries.js';
import { ApiError } from '../api/client.js';
import StatusPill from '../components/StatusPill.jsx';
import Skeleton from '../components/Skeleton.jsx';
import { formatDateTime } from '../lib/format.js';
import { themeVars } from '../lib/color.js';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/**
 * The one page reachable without logging in - GET
 * /api/cargo/track/{waybillNumber}?phone=... (permitAll()'d server-side,
 * and carved out of node-bff's normal requireSession gate too, see
 * node-bff/src/routes/api.js). A consignor/consignee is often not a
 * registered platform customer at all, so this is a two-factor lookup
 * (waybill number + a matching phone) rather than an ownership-scoped
 * query - see WaybillTrackingView's javadoc for exactly which fields this
 * intentionally does and doesn't expose.
 */
export default function Track() {
  const [waybillNumber, setWaybillNumber] = useState('');
  const [phone, setPhone] = useState('');
  const [submitted, setSubmitted] = useState(null);

  const trackQuery = useTrackWaybill(submitted?.waybillNumber, submitted?.phone);

  function handleSubmit(event) {
    event.preventDefault();
    setSubmitted({ waybillNumber: waybillNumber.trim(), phone: phone.trim() });
  }

  const notFound = trackQuery.isError && trackQuery.error instanceof ApiError && trackQuery.error.status === 404;

  return (
    <div className="mx-auto max-w-md">
      <h1 className="mb-1 text-2xl font-bold text-ink">Track a shipment</h1>
      <p className="mb-6 text-sm text-ink-muted">
        Enter the waybill number and a phone number on the shipment (either the sender's or receiver's).
      </p>

      <form onSubmit={handleSubmit} className="mb-6 flex flex-col gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <label className="block">
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Waybill number</span>
          <input value={waybillNumber} onChange={(e) => setWaybillNumber(e.target.value)} placeholder="e.g. DBC-CARGO-2026-1234567" className={`${inputClass} w-full`} />
        </label>
        <label className="block">
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Phone number</span>
          <input value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="+2519xxxxxxxx" className={`${inputClass} w-full`} />
        </label>
        <button
          type="submit"
          disabled={!waybillNumber.trim() || !phone.trim()}
          className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50"
        >
          Track
        </button>
      </form>

      {trackQuery.isLoading && <Skeleton className="h-32 w-full" />}

      {notFound && (
        <div className="rounded-xl border border-danger/30 bg-danger-light px-4 py-3 text-sm text-danger">
          No shipment found for that waybill number and phone number. Double-check both and try again.
        </div>
      )}
      {trackQuery.isError && !notFound && (
        <div className="rounded-xl border border-danger/30 bg-danger-light px-4 py-3 text-sm text-danger">
          {trackQuery.error?.message || 'Something went wrong.'}
        </div>
      )}

      {trackQuery.data && (
        <div
          className="overflow-hidden rounded-xl border border-slate-200 bg-surface"
          style={trackQuery.data.branding ? { ...themeVars(trackQuery.data.branding.brandColor, 'brand'), ...themeVars(trackQuery.data.branding.accentColor, 'accent') } : undefined}
        >
          {trackQuery.data.branding && (
            <div className="flex items-center gap-2 bg-brand px-5 py-2.5 text-white">
              <img
                src={trackQuery.data.branding.logoUrl || '/brand/bustix-mark.svg'}
                alt=""
                className="h-6 w-auto max-w-[7rem] object-contain"
              />
              <span className="text-sm font-semibold">{trackQuery.data.branding.displayName}</span>
            </div>
          )}
          <div className="p-5">
          <div className="mb-4 flex items-center justify-between">
            <span className="font-mono text-xs text-ink-muted">{trackQuery.data.waybillNumber}</span>
            <StatusPill status={trackQuery.data.status} />
          </div>
          {trackQuery.data.origin && (
            <p className="mb-4 text-sm font-semibold text-ink">
              {trackQuery.data.origin} <span className="text-ink-muted">&rarr;</span> {trackQuery.data.destination}
              {trackQuery.data.departureAt && <span className="font-normal text-ink-muted"> · departs {formatDateTime(trackQuery.data.departureAt)}</span>}
            </p>
          )}
          <dl className="grid grid-cols-2 gap-3 border-t border-slate-100 pt-4 text-sm">
            <div>
              <dt className="text-ink-muted">Issued</dt>
              <dd className="text-ink">{formatDateTime(trackQuery.data.issuedAt)}</dd>
            </div>
            <div>
              <dt className="text-ink-muted">Dispatched</dt>
              <dd className="text-ink">{formatDateTime(trackQuery.data.dispatchedAt)}</dd>
            </div>
            <div>
              <dt className="text-ink-muted">Arrived</dt>
              <dd className="text-ink">{formatDateTime(trackQuery.data.arrivedAt)}</dd>
            </div>
            <div>
              <dt className="text-ink-muted">Collected</dt>
              <dd className="text-ink">{formatDateTime(trackQuery.data.collectedAt)}</dd>
            </div>
          </dl>

          {(trackQuery.data.operatorSupportPhone || trackQuery.data.operatorSupportEmail) && (
            <p className="mt-4 border-t border-slate-100 pt-4 text-xs text-ink-muted">
              Operator support:{' '}
              {[trackQuery.data.operatorSupportPhone, trackQuery.data.operatorSupportEmail].filter(Boolean).join(' · ')}
            </p>
          )}
          </div>
        </div>
      )}
    </div>
  );
}
