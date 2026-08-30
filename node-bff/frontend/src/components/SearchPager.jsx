/**
 * "Showing 1–20 of 47" + Prev/Next for the trip-search results pages. The
 * total comes from GET /api/trips/search's X-Total-Count header (see
 * apiGetWithCount) - previously fetched but never shown, so a busy route's
 * results were silently capped at 20 with no hint more existed.
 */
export default function SearchPager({ page, pageSize, shown, total, onPageChange }) {
  const first = total === 0 ? 0 : page * pageSize + 1;
  const last = page * pageSize + shown;
  const hasPrev = page > 0;
  const hasNext = last < total;

  if (!hasPrev && !hasNext) {
    return <p className="mt-4 text-sm text-ink-muted">{total} {total === 1 ? 'trip' : 'trips'}</p>;
  }

  function go(next) {
    onPageChange(next);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  return (
    <div className="mt-6 flex items-center justify-between border-t border-slate-200 pt-4">
      <span className="text-sm text-ink-muted">
        Showing {first}–{last} of {total}
      </span>
      <div className="flex items-center gap-2">
        <button
          type="button"
          disabled={!hasPrev}
          onClick={() => go(page - 1)}
          className="rounded-lg border border-slate-200 px-3 py-1.5 text-sm font-medium text-ink hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40"
        >
          Previous
        </button>
        <button
          type="button"
          disabled={!hasNext}
          onClick={() => go(page + 1)}
          className="rounded-lg border border-slate-200 px-3 py-1.5 text-sm font-medium text-ink hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40"
        >
          Next
        </button>
      </div>
    </div>
  );
}
