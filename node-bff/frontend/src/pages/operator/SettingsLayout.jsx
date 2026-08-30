import { NavLink, Outlet } from 'react-router-dom';

const tabClass = ({ isActive }) =>
  `border-b-2 px-1 pb-2 text-sm font-medium transition-colors ${
    isActive ? 'border-brand text-brand' : 'border-transparent text-ink-muted hover:text-ink'
  }`;

/**
 * operator_admin config hub - one nav entry ("Settings"), three tabs. Folds
 * what used to be the separate /operator/refund-policies and
 * /operator/cargo-rates pages in alongside the business-value/contact
 * overrides, so an operator's whole configuration surface lives under
 * /operator/settings. Each tab is its own route
 * (/operator/settings[/refund-policies|/cargo-rates]) so it stays
 * deep-linkable; the old paths redirect here (see App.jsx).
 */
export default function OperatorSettingsLayout() {
  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold text-ink">Settings</h1>
      <nav className="mb-6 flex gap-6 border-b border-slate-200">
        <NavLink to="/operator/settings" end className={tabClass}>
          General
        </NavLink>
        <NavLink to="/operator/settings/refund-policies" className={tabClass}>
          Refund Policies
        </NavLink>
        <NavLink to="/operator/settings/cargo-rates" className={tabClass}>
          Cargo Rates
        </NavLink>
      </nav>
      <Outlet />
    </div>
  );
}
