/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        brand: { DEFAULT: '#1D4ED8', dark: '#1E3A8A', light: '#EFF4FF' },
        accent: { DEFAULT: '#F59E0B', dark: '#B45309', light: '#FEF3E2' },
        success: { DEFAULT: '#16A34A', light: '#E9F9EF' },
        danger: { DEFAULT: '#DC2626', light: '#FDECEC' },
        warning: { DEFAULT: '#D97706', light: '#FEF3E2' },
        surface: '#FFFFFF',
        ink: '#0F172A',
        'ink-muted': '#64748B',
      },
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'monospace'],
      },
    },
  },
  plugins: [],
};
