/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: { brand: { 50: '#fdf2f7', 100: '#fce7f1', 500: '#c2417a', 600: '#a8326a', 700: '#7c2d52' } },
    },
  },
  plugins: [],
}
