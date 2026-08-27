import { useState } from 'react';
import { useOperatorDashboard } from '../../api/queries.js';
import StatCard from '../../components/StatCard.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import PeriodSelector, { loadPeriod } from '../../components/PeriodSelector.jsx';
import RankedBarList from '../../components/RankedBarList.jsx';
import TrendLineChart from '../../components/charts/TrendLineChart.jsx';
import BreakdownDonut from '../../components/charts/BreakdownDonut.jsx';
import { DashboardSection, RecentBookingsPanel, DeparturesPanel } from '../../components/DashboardPanels.jsx';
import {
  BRAND,
  DANGER,
  BOOKING_STATUS_COLORS,
  CARGO_STATUS_COLORS,
  CHANNEL_COLORS,
  PAYMENT_METHOD_COLORS,
  colorFor,
} from '../../lib/chartTheme.js';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

function slices(rows = [], colorMap, metric = 'count') {
  return rows.map((r, i) => ({
    key: r.key,
    value: metric === 'money' ? Number(r.amount) : Number(r.count),
    color: colorFor(colorMap, r.key, i),
  }));
}

/**
 * operator_admin analytics dashboard (GET /api/operator/dashboard?period=).
 * Rendered at "/" for an operator_admin via App.jsx's RoleHome, and
 * reachable directly at /operator/dashboard.
 */
export default function OperatorDashboard() {
  const [period, setPeriod] = useState(loadPeriod);
  const { data, isError, error, refetch, isFetching } = useOperatorDashboard(period);

  return (
    <div className="flex flex-col gap-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold text-ink">Dashboard</h1>
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
            <StatCard
              label="Cancellations"
              value={data.bookings.cancelledCurrent}
              spark={data.series.cancellations}
              hint={`in the last ${period}`}
            />
            <StatCard
              label="Upcoming trips"
              value={data.fleet.upcomingTrips}
              hint={`${data.fleet.activeBuses} buses · ${data.fleet.activeRoutes} routes`}
            />
            <StatCard label="Active waybills" value={data.cargo.activeWaybills} />
            <StatCard
              label="Cargo revenue"
              value={formatCurrency(data.cargo.revenueCurrent)}
              mono
              delta={{ deltaPct: data.cargo.revenueDeltaPct }}
            />
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

          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <BreakdownDonut title="Booking channel" data={slices(data.breakdowns.channel, CHANNEL_COLORS)} />
            <BreakdownDonut title="Booking status" data={slices(data.breakdowns.status, BOOKING_STATUS_COLORS)} />
            <BreakdownDonut title="Cargo status" data={slices(data.breakdowns.cargoStatus, CARGO_STATUS_COLORS)} />
            <BreakdownDonut
              title="Payments collected"
              metric="money"
              data={slices(data.breakdowns.paymentMethod, PAYMENT_METHOD_COLORS, 'money')}
            />
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
            <DashboardSection title="Seat occupancy — upcoming">
              <RankedBarList
                items={data.occupancy.map((o) => ({
                  label: o.routeName || 'Trip',
                  value: `${Math.round(o.rate * 100)}%`,
                  bar: o.rate,
                  sub: `${o.seatsBooked}/${o.capacity} seats · ${formatDateTime(o.departureAt)}`,
                }))}
                emptyTitle="No upcoming departures"
              />
            </DashboardSection>
          </div>

          <div className="grid gap-8 lg:grid-cols-2">
            <DashboardSection title="Recent bookings">
              <RecentBookingsPanel bookings={data.recentBookings} />
            </DashboardSection>
            <DashboardSection title="Upcoming departures">
              <DeparturesPanel departures={data.upcomingDepartures} />
            </DashboardSection>
          </div>
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
