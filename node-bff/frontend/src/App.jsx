import { lazy, Suspense } from 'react';
import { Routes, Route, Link, Navigate } from 'react-router-dom';
import { useAuth } from './auth/AuthContext.jsx';
import RequireRole from './auth/RequireRole.jsx';
import AppShell from './layout/AppShell.jsx';
import PublicShell from './layout/PublicShell.jsx';
import Home from './pages/customer/Home.jsx';
import SearchResults from './pages/customer/SearchResults.jsx';
import SeatSelection from './pages/customer/SeatSelection.jsx';
import BookingDetail from './pages/customer/BookingDetail.jsx';
import MyBookings from './pages/customer/MyBookings.jsx';
import Reschedule from './pages/customer/Reschedule.jsx';
import AgentSearch from './pages/agent/Search.jsx';
import AgentSearchResults from './pages/agent/SearchResults.jsx';
import AgentSeatSelection from './pages/agent/SeatSelection.jsx';
import AgentBookings from './pages/agent/Bookings.jsx';
import AgentBookingDetail from './pages/agent/BookingDetail.jsx';
import AgentReschedule from './pages/agent/Reschedule.jsx';
import AgentDashboard from './pages/agent/Dashboard.jsx';
// The operator/platform dashboards pull in Recharts (~+115KB gzip) - lazy so
// it's only fetched for those two roles, not on the customer/guest first load.
const OperatorDashboard = lazy(() => import('./pages/operator/Dashboard.jsx'));
const PlatformDashboard = lazy(() => import('./pages/platform/Dashboard.jsx'));
import OperatorBuses from './pages/operator/Buses.jsx';
import OperatorRoutes from './pages/operator/Routes.jsx';
import OperatorTrips from './pages/operator/Trips.jsx';
import OperatorRefundPolicies from './pages/operator/RefundPolicies.jsx';
import OperatorSettingsLayout from './pages/operator/SettingsLayout.jsx';
import OperatorSettings from './pages/operator/Settings.jsx';
import OperatorBranding from './pages/operator/Branding.jsx';
import PlatformOperators from './pages/platform/Operators.jsx';
import Waybills from './pages/cargo/Waybills.jsx';
import WaybillDetail from './pages/cargo/WaybillDetail.jsx';
import OperatorCargoRates from './pages/operator/CargoRates.jsx';
import Track from './pages/Track.jsx';
import TrackBooking from './pages/TrackBooking.jsx';
import MyShipments from './pages/customer/MyShipments.jsx';
import MyShipmentDetail from './pages/customer/MyShipmentDetail.jsx';
import RequestShipment from './pages/customer/RequestShipment.jsx';

/**
 * "/" is role-aware: staff land on their own dashboard, everyone else
 * (customer + logged-out guest) gets the marketplace search Home. Before
 * this, every role landed on the customer search box. Real authorization is
 * server-side on each dashboard endpoint (@PreAuthorize); this is UX routing
 * only, same as RequireRole.
 */
function DashboardFallback() {
  return (
    <div className="flex min-h-[40vh] items-center justify-center">
      <div className="h-8 w-8 animate-spin rounded-full border-4 border-brand-light border-t-brand" />
    </div>
  );
}

/** Wraps the lazily-loaded (Recharts-heavy) operator/platform dashboards. */
function LazyDashboard({ children }) {
  return <Suspense fallback={<DashboardFallback />}>{children}</Suspense>;
}

function RoleHome() {
  const { hasRole } = useAuth();
  if (hasRole('operator_admin')) return <LazyDashboard><OperatorDashboard /></LazyDashboard>;
  if (hasRole('agent')) return <AgentDashboard />;
  if (hasRole('platform_admin')) return <LazyDashboard><PlatformDashboard /></LazyDashboard>;
  return <Home />;
}

function RootLayout() {
  const { isLoading, authenticated } = useAuth();
  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-brand-light border-t-brand" />
      </div>
    );
  }
  return authenticated ? <AppShell /> : <PublicShell />;
}

function NotFound() {
  return (
    <div className="py-16 text-center">
      <p className="text-xl font-semibold text-ink">Page not found</p>
      <Link to="/" className="mt-2 inline-block text-sm text-brand hover:underline">
        Back home
      </Link>
    </div>
  );
}

export default function App() {
  return (
    <Routes>
      <Route element={<RootLayout />}>
        <Route path="/" element={<RoleHome />} />
        {/* Stable deep-links for the nav "Dashboard" entries - RoleHome
            renders these at "/" too, but a bookmarkable path per role is
            clearer and matches how the other staff sections are wired. */}
        <Route
          path="/operator/dashboard"
          element={
            <RequireRole role="operator_admin">
              <LazyDashboard>
                <OperatorDashboard />
              </LazyDashboard>
            </RequireRole>
          }
        />
        <Route
          path="/agent/dashboard"
          element={
            <RequireRole role="agent">
              <AgentDashboard />
            </RequireRole>
          }
        />
        <Route
          path="/platform/dashboard"
          element={
            <RequireRole role="platform_admin">
              <LazyDashboard>
                <PlatformDashboard />
              </LazyDashboard>
            </RequireRole>
          }
        />
        {/* Public - a guest with no account searches/picks a seat/books the
            same as a logged-in customer; see SeatSelection.jsx for how it
            branches between useCreateBooking and useCreateGuestBooking. */}
        <Route path="/search" element={<SearchResults />} />
        <Route path="/trips/:tripId" element={<SeatSelection />} />
        {/* Reachable logged-out too, right after a guest booking (via
            location.state from SeatSelection's navigate()) - see
            BookingDetail.jsx's own authenticated-vs-guest branch. A
            revisit/refresh with no login and no location.state has no
            other way to recover a guest booking, by design - see
            /track-booking below. */}
        <Route path="/bookings/:bookingId" element={<BookingDetail />} />
        <Route
          path="/my-bookings"
          element={
            <RequireRole role="customer">
              <MyBookings />
            </RequireRole>
          }
        />
        <Route
          path="/my-shipments"
          element={
            <RequireRole role="customer">
              <MyShipments />
            </RequireRole>
          }
        />
        <Route
          path="/my-shipments/request"
          element={
            <RequireRole role="customer">
              <RequestShipment />
            </RequireRole>
          }
        />
        <Route
          path="/my-shipments/:waybillId"
          element={
            <RequireRole role="customer">
              <MyShipmentDetail />
            </RequireRole>
          }
        />
        <Route
          path="/bookings/:bookingId/reschedule"
          element={
            <RequireRole role="customer">
              <Reschedule />
            </RequireRole>
          }
        />
        <Route
          path="/agent"
          element={
            <RequireRole role="agent">
              <AgentSearch />
            </RequireRole>
          }
        />
        <Route
          path="/agent/search"
          element={
            <RequireRole role="agent">
              <AgentSearchResults />
            </RequireRole>
          }
        />
        <Route
          path="/agent/trips/:tripId"
          element={
            <RequireRole role="agent">
              <AgentSeatSelection />
            </RequireRole>
          }
        />
        <Route
          path="/agent/bookings"
          element={
            <RequireRole role="agent">
              <AgentBookings />
            </RequireRole>
          }
        />
        <Route
          path="/agent/bookings/:bookingId"
          element={
            <RequireRole role="agent">
              <AgentBookingDetail />
            </RequireRole>
          }
        />
        <Route
          path="/agent/bookings/:bookingId/reschedule"
          element={
            <RequireRole role="agent">
              <AgentReschedule />
            </RequireRole>
          }
        />
        <Route
          path="/operator/buses"
          element={
            <RequireRole role="operator_admin">
              <OperatorBuses />
            </RequireRole>
          }
        />
        <Route
          path="/operator/routes"
          element={
            <RequireRole role="operator_admin">
              <OperatorRoutes />
            </RequireRole>
          }
        />
        <Route
          path="/operator/trips"
          element={
            <RequireRole role="operator_admin">
              <OperatorTrips />
            </RequireRole>
          }
        />
        {/* Operator config hub - "Settings" is the single nav entry; Refund
            Policies and Cargo Rates are tabs (their own child routes, still
            deep-linkable). The old top-level paths redirect in (below). */}
        <Route
          path="/operator/settings"
          element={
            <RequireRole role="operator_admin">
              <OperatorSettingsLayout />
            </RequireRole>
          }
        >
          <Route index element={<OperatorSettings />} />
          <Route path="refund-policies" element={<OperatorRefundPolicies />} />
          <Route path="cargo-rates" element={<OperatorCargoRates />} />
          <Route path="branding" element={<OperatorBranding />} />
        </Route>
        <Route path="/operator/refund-policies" element={<Navigate to="/operator/settings/refund-policies" replace />} />
        <Route path="/operator/cargo-rates" element={<Navigate to="/operator/settings/cargo-rates" replace />} />
        <Route
          path="/platform/operators"
          element={
            <RequireRole role="platform_admin">
              <PlatformOperators />
            </RequireRole>
          }
        />
        <Route path="/track" element={<Track />} />
        <Route path="/track-booking" element={<TrackBooking />} />
        <Route
          path="/cargo"
          element={
            <RequireRole roles={['agent', 'operator_admin']}>
              <Waybills />
            </RequireRole>
          }
        />
        <Route
          path="/cargo/waybills/:waybillId"
          element={
            <RequireRole roles={['agent', 'operator_admin']}>
              <WaybillDetail />
            </RequireRole>
          }
        />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}
