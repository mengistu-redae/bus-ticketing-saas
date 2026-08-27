import { useState } from 'react';
import { Link } from 'react-router-dom';
import { usePlatformDashboard } from '../../api/queries.js';
import StatCard from '../../components/StatCard.jsx';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import PeriodSelector, { loadPeriod } from '../../components/PeriodSelector.jsx';
import RankedBarList from '../../components/RankedBarList.jsx';
import TrendLineChart from '../../components/charts/TrendLineChart.jsx';
import BreakdownDonut from '../../components/charts/BreakdownDonut.jsx';
import { DashboardSection } from '../../components/DashboardPanels.jsx';
import { BRAND, DANGER, BOOKING_STATUS_COLORS, CHANNEL_COLORS, colorFor } from '../../lib/chartTheme.js';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

function slices(rows = [], colorMap) {
  return rows.map((r, i) => ({ key: r.key, value: Number(r.count), color: colorFor(colorMap, r.key, i) }));
}

/**
 * platform_admin analytics dashboard (GET /api/platform/dashboard?period=) -
 * cross-tenant. Rendered at "/" for a platform_admin via App.jsx's RoleHome,
 * and reachable directly at /platform/dashboard.
 */
export default function PlatformDashboard() {
  const [period, setPeriod] = useState(loadPeriod);
  const { data, isError, error, refetch, isFetching } = usePlatformDashboard(period);

  return (
    <div className="flex flex-col gap-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold text-ink">Platform dashboard</h1>
        <PeriodSelector value={period} onChange={setPeriod} />
      </div>

      {!data && isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!data && !isError && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-24 w-full" />
          ))}
        </div>
      )}
      {data && isError && <ErrorBanner message={error?.message} onRetry={refetch} />}

      {data && (
        <div className={`flex flex-col gap-8 transition-opacity ${isFetching ? 'opacity-60' : ''}`}>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard
              label="Operators"
              value={data.operators.total}
              hint={`${data.operators.active} active · ${data.operators.inactive} inactive`}
            />
            <StatCard
              label="Bookings"
              value={data.bookings.current}
              delta={data.bookings}
              spark={data.series.bookings}
              hint={`vs ${data.bookings.previous} prior ${period}`}
            />
            <StatCard
              label="Revenue"
              value={formatCurrency(data.revenue.current)}
              mono
              delta={data.revenue}
              spark={data.series.revenue}
              hint={`vs ${formatCurrency(data.revenue.previous)} prior`}
            />
            <StatCard label="Upcoming trips" value={data.upcomingTrips} />
            <StatCard label="Active waybills" value={data.cargo.activeWaybills} />
            <StatCard label="Pending cargo requests" value={data.cargo.pendingRequests} />
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <ChartCard title="Revenue">
              <TrendLineChart
                days={data.series.days}
                money
                series={[{ key: 'revenue', label: 'Revenue', values: data.series.revenue, color: BRAND }]}
              />
            </ChartCard>
            <ChartCard title="Bookings">
              <TrendLineChart
                days={data.series.days}
                series={[
                  { key: 'bookings', label: 'Bookings', values: data.series.bookings, color: BRAND },
                  { key: 'cancellations', label: 'Cancelled', values: data.series.cancellations, color: DANGER },
                ]}
              />
            </ChartCard>
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <BreakdownDonut title="Booking channel" data={slices(data.breakdowns.channel, CHANNEL_COLORS)} />
            <BreakdownDonut title="Booking status" data={slices(data.breakdowns.status, BOOKING_STATUS_COLORS)} />
          </div>

          <div className="grid gap-8 lg:grid-cols-2">
            <DashboardSection title="Top routes by revenue">
              <RankedBarList
                items={data.topRoutes.map((r) => ({
                  label: r.routeName,
                  value: r.revenue,
                  sub: `${r.bookings} booking${r.bookings === 1 ? '' : 's'}`,
                }))}
                format={formatCurrency}
                emptyTitle="No confirmed bookings in this period"
              />
            </DashboardSection>
            <DashboardSection title="Top operators">
              {data.topOperators.length === 0 ? (
                <EmptyState title="No bookings in this period" />
              ) : (
                <div className="overflow-x-auto rounded-xl border border-slate-200 bg-surface shadow-sm">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-slate-200 text-left text-xs uppercase tracking-wide text-ink-muted">
                        <th className="px-4 py-2 font-semibold">Operator</th>
                        <th className="px-4 py-2 text-right font-semibold">Bookings</th>
                        <th className="px-4 py-2 text-right font-semibold">Revenue</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.topOperators.map((o) => (
                        <tr key={o.operatorId} className="border-b border-slate-100 last:border-0">
                          <td className="px-4 py-2 text-ink">{o.name}</td>
                          <td className="px-4 py-2 text-right tabular-nums text-ink">{o.bookings}</td>
                          <td className="px-4 py-2 text-right font-mono tabular-nums text-ink">
                            {formatCurrency(o.revenue)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </DashboardSection>
          </div>

          <DashboardSection
            title="Newest operators"
            cta={
              <Link to="/platform/operators" className="text-xs font-medium text-brand hover:underline">
                Manage
              </Link>
            }
          >
            {data.recentOperators.length === 0 ? (
              <EmptyState title="No operators yet" />
            ) : (
              <div className="grid gap-2 sm:grid-cols-2">
                {data.recentOperators.map((o) => (
                  <div
                    key={o.id}
                    className="flex items-center justify-between rounded-xl border border-slate-200 bg-surface p-3 shadow-sm"
                  >
                    <div>
                      <p className="text-sm font-medium text-ink">{o.name}</p>
                      <p className="text-xs text-ink-muted">{formatDateTime(o.createdAt)}</p>
                    </div>
                    <StatusPill status={o.status} />
                  </div>
                ))}
              </div>
            )}
          </DashboardSection>
        </div>
      )}
    </div>
  );
}

function ChartCard({ title, children }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-surface p-4 shadow-sm">
      <p className="mb-3 text-xs font-semibold uppercase tracking-wide text-ink-muted">{title}</p>
      {children}
    </div>
  );
}
