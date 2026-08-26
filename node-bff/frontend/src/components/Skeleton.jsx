export default function Skeleton({ className = '' }) {
  return <div className={`animate-pulse rounded-lg bg-slate-200 ${className}`} />;
}

export function TripCardSkeleton() {
  return (
    <div className="rounded-xl border border-slate-200 bg-surface p-4 shadow-sm">
      <Skeleton className="mb-3 h-3 w-24" />
      <Skeleton className="mb-2 h-6 w-64" />
      <Skeleton className="mb-4 h-3 w-40" />
      <div className="flex items-center justify-between">
        <Skeleton className="h-4 w-20" />
        <Skeleton className="h-9 w-28 rounded-lg" />
      </div>
    </div>
  );
}
