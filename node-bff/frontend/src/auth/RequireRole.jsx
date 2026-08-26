import { Navigate } from 'react-router-dom';
import { useAuth } from './AuthContext.jsx';

/**
 * UX-only gate - hides/redirects in the UI so a customer doesn't stumble
 * into an operator-admin page and vice versa. The real authorization
 * boundary is entirely server-side (@PreAuthorize in spring-boot-api,
 * already the enforced check on every endpoint this app calls) - a
 * determined user bypassing this component gets 403s from the API, not
 * unauthorized data.
 *
 * Pass either `role` (single, every pre-cargo call site) or `roles` (an
 * array) - cargo waybill pages are reachable by AGENT and OPERATOR_ADMIN
 * alike (identical backend permissions on those endpoints), unlike every
 * other staff page here which is single-role.
 */
export default function RequireRole({ role, roles, children }) {
  const { isLoading, authenticated, hasRole } = useAuth();

  if (isLoading) {
    return null;
  }
  if (!authenticated) {
    window.location.href = '/auth/login';
    return null;
  }
  const allowed = roles ? roles.some(hasRole) : hasRole(role);
  if (!allowed) {
    return <Navigate to="/" replace />;
  }
  return children;
}
