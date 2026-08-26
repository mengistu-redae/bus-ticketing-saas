import { useEffect, useState } from 'react';
import QRCode from 'qrcode';
import Skeleton from './Skeleton.jsx';

/**
 * Renders one passenger's digital boarding pass as a QR code, encoding
 * {type, bookingId, seatId} - enough for a future staff scan-to-check-in
 * flow to resolve straight into BoardingController's existing check-in
 * endpoint, without a new backend lookup. Display-only in this pass -
 * nothing currently scans it.
 */
export default function BoardingPassQr({ bookingId, seatId, size = 112 }) {
  const [dataUrl, setDataUrl] = useState(null);

  useEffect(() => {
    let cancelled = false;
    const payload = JSON.stringify({ type: 'bustix.boarding_pass.v1', bookingId, seatId });
    QRCode.toDataURL(payload, { width: size, margin: 1 })
      .then((url) => {
        if (!cancelled) setDataUrl(url);
      })
      .catch(() => {
        if (!cancelled) setDataUrl(null);
      });
    return () => {
      cancelled = true;
    };
  }, [bookingId, seatId, size]);

  if (!dataUrl) return <Skeleton className="h-28 w-28 rounded-lg" />;
  return (
    <img
      src={dataUrl}
      width={size}
      height={size}
      alt="Boarding pass QR code"
      className="rounded-lg border border-slate-200"
    />
  );
}
