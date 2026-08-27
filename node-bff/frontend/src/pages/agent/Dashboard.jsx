import { Link } from 'react-router-dom';
import { useAgentDashboard } from '../../api/queries.js';
import StatCard from '../../components/StatCard.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { DashboardSection, RecentBookingsPanel, DeparturesPanel } from '../../components/DashboardPanels.jsx';

/**
 * agent landing page (GET /api/agent/dashboard) - a trimmed, counter-desk
 * view. Rendered at "/" for an agent via App.jsx's RoleHome, and reachable
 * directly at /agent/dashboard.
 */
export default function AgentDashboard() {
  const { data, isLoading, isError, error, refetch } = useAgentDashboard();

  if (isLoading) {
    return (
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <Skeleton key={i} className="h-24 w-full" />
        ))}
      </div>
    );
  }
  if (isError) {
    return <ErrorBanner message={error?.message} onRetry={refetch} />;
  }

  const {
    myCounterBookings,
    operatorBookingsToday,
    pendingCargoRequests,
    activeWaybills,
    sparkline14d,
    departuresNext24h,
    recentBookings,
  } = data;

  return (
    <div className="flex flex-col gap-8">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-ink">Dashboard</h1>
        <Link to="/agent" className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark">
          New booking
        </Link>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="My counter bookings today"
          value={myCounterBookings.today}
          spark={sparkline14d}
          hint={`${myCounterBookings.last7d} in the last 7 days`}
        />
        <StatCard label="Operator bookings today" value={operatorBookingsToday} />
        <StatCard label="Pending cargo requests" value={pendingCargoRequests} hint="awaiting confirm & issue" />
        <StatCard label="Active waybills" value={activeWaybills} />
      </div>

      <div className="grid gap-8 lg:grid-cols-2">
        <DashboardSection title="Departures in the next 24h">
          <DeparturesPanel departures={departuresNext24h} />
        </DashboardSection>
        <DashboardSection
          title="Recent bookings"
          cta={
            <Link to="/agent/bookings" className="text-xs font-medium text-brand hover:underline">
              View all
            </Link>
          }
        >
          <RecentBookingsPanel bookings={recentBookings} linkBase="/agent/bookings" />
        </DashboardSection>
      </div>
    </div>
  );
}
