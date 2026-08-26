import { useEffect, useRef, useState } from 'react';
import { useLocationSuggestions } from '../api/queries.js';

/**
 * From/To text input with a live-filtering dropdown of matching city names,
 * backed by GET /api/trips/locations (substring, case-insensitive, drawn
 * from every active route's origin AND destination - see TripController).
 * Shared by the customer Home page and the agent Search page, the two
 * trip-search entry points - both previously plain text inputs with no
 * feedback until the form was submitted.
 *
 * Debounced client-side (250ms) on top of the query already being disabled
 * below 2 characters server-side - keeps a fast typist from firing a
 * request per keystroke. Typing a value that doesn't match anything simply
 * shows no dropdown; the field still submits as free text either way (the
 * backend's own search is an exact case-insensitive match, so a chosen
 * suggestion is what actually guarantees a hit, but typing a full city name
 * without ever opening the dropdown keeps working exactly as before).
 */
export default function LocationAutocomplete({ id, label, value, onChange, placeholder, required }) {
  const [debounced, setDebounced] = useState(value);
  const [open, setOpen] = useState(false);
  const [highlighted, setHighlighted] = useState(-1);
  const containerRef = useRef(null);

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), 250);
    return () => clearTimeout(timer);
  }, [value]);

  const { data: suggestions = [] } = useLocationSuggestions(debounced);
  const showDropdown = open && suggestions.length > 0;

  // Close on outside click - the only way this dropdown otherwise closes is
  // selecting an option or blurring, and blur alone would fire before a
  // click on an option lands (see onMouseDown below).
  useEffect(() => {
    function handleClickOutside(event) {
      if (containerRef.current && !containerRef.current.contains(event.target)) {
        setOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  function selectSuggestion(city) {
    onChange(city);
    setOpen(false);
    setHighlighted(-1);
  }

  function handleKeyDown(event) {
    if (!showDropdown) return;
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setHighlighted((i) => Math.min(i + 1, suggestions.length - 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setHighlighted((i) => Math.max(i - 1, 0));
    } else if (event.key === 'Enter' && highlighted >= 0) {
      event.preventDefault();
      selectSuggestion(suggestions[highlighted]);
    } else if (event.key === 'Escape') {
      setOpen(false);
    }
  }

  return (
    <label htmlFor={id} className="relative block flex-1 text-left" ref={containerRef}>
      <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{label}</span>
      <input
        id={id}
        required={required}
        autoComplete="off"
        value={value}
        onChange={(e) => {
          onChange(e.target.value);
          setOpen(true);
          setHighlighted(-1);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={handleKeyDown}
        placeholder={placeholder}
        className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20"
      />
      {showDropdown && (
        <ul className="absolute z-10 mt-1 w-full max-h-56 overflow-auto rounded-lg border border-slate-200 bg-surface py-1 shadow-lg">
          {suggestions.map((city, index) => (
            <li key={city}>
              <button
                type="button"
                // mousedown (not click) fires before the input's blur, so
                // the outside-click handler above never gets a chance to
                // close this out from under the selection.
                onMouseDown={(e) => {
                  e.preventDefault();
                  selectSuggestion(city);
                }}
                onMouseEnter={() => setHighlighted(index)}
                className={`block w-full px-3 py-1.5 text-left text-sm ${
                  index === highlighted ? 'bg-brand-light/40 text-brand' : 'text-ink hover:bg-brand-light/20'
                }`}
              >
                {city}
              </button>
            </li>
          ))}
        </ul>
      )}
    </label>
  );
}
