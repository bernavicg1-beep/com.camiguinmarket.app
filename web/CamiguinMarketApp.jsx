import React, { useEffect, useMemo, useState } from 'react';

// How often the app re-checks the perishable items while it is open.
const EXPIRY_CHECK_MS = 30_000;

// Where the listings are kept so they survive a page reload.
const STORAGE_KEY = 'camiguin-market.listings';

/**
 * A perishable listing is expired once its expiration time is reached.
 * Non-perishable listings never expire.
 */
function isExpired(listing, now) {
  return Boolean(
    listing.isPerishable && listing.expiresAt && listing.expiresAt <= now
  );
}

/**
 * Read the saved listings. Returns null when nothing usable is stored, so the
 * samples are only used on a first visit.
 */
function loadStoredListings() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return null;
    return parsed.filter(
      (item) => item && typeof item.id === 'string' && typeof item.title === 'string'
    );
  } catch {
    return null;
  }
}

/** Best-effort save: private mode or a full quota must not break the app. */
function saveListings(listings) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(listings));
  } catch {
    // Ignore: the list still works for this session.
  }
}

/**
 * Sample listings so the list is not empty on first load.
 * `expiresAt` is relative to load time, and the Pastel entry is already
 * expired on purpose: the page-load purge removes it.
 */
function buildSeedListings(now) {
  const minute = 60_000;
  const hour = 60 * minute;

  return [
    {
      id: 'seed-lapu-lapu',
      title: 'Fresh Lapu-Lapu (1 kg)',
      price: 350,
      isPerishable: true,
      expiresAt: now + 6 * hour,
      createdAt: now - 20 * minute
    },
    {
      id: 'seed-lanzones',
      title: 'Camiguin Lanzones (1 kg)',
      price: 180,
      isPerishable: true,
      expiresAt: now + 2 * 24 * hour,
      createdAt: now - 90 * minute
    },
    {
      id: 'seed-pastel',
      title: 'Pastel (box of 6)',
      price: 120,
      isPerishable: true,
      // Already expired: filtered out as soon as the page loads.
      expiresAt: now - 45 * minute,
      createdAt: now - 5 * hour
    },
    {
      id: 'seed-banig',
      title: 'Handwoven Banig Mat',
      price: 900,
      isPerishable: false,
      expiresAt: null,
      createdAt: now - 3 * hour
    },
    {
      id: 'seed-vinegar',
      title: 'Coconut Vinegar (1 L)',
      price: 150,
      isPerishable: false,
      expiresAt: null,
      createdAt: now - 4 * hour
    }
  ];
}

export default function CamiguinMarketApp() {
  // New listing form
  const [title, setTitle] = useState('');
  const [price, setPrice] = useState('');
  const [isPerishable, setIsPerishable] = useState(false);
  const [expiresAt, setExpiresAt] = useState('');

  // Saved listings (restored from storage, or samples on a first visit)
  // plus the clock used to filter them.
  const [listings, setListings] = useState(
    () => loadStoredListings() ?? buildSeedListings(Date.now())
  );
  const [now, setNow] = useState(() => Date.now());

  // User data
  const user = {
    fullName: 'Bernavic Gamo',
    username: 'bernavicg',
    email: 'bernavicg1@gmail.com'
  };

  // On page load: drop every perishable listing that has already expired,
  // then keep checking on an interval so the list stays live.
  useEffect(() => {
    const purgeExpired = () => {
      const current = Date.now();
      setNow(current);
      setListings((prev) => prev.filter((item) => !isExpired(item, current)));
    };

    purgeExpired();
    const timer = setInterval(purgeExpired, EXPIRY_CHECK_MS);
    return () => clearInterval(timer);
  }, []);

  // Persist on every change, including the purge that removes expired items.
  useEffect(() => {
    saveListings(listings);
  }, [listings]);

  // Only active (non-expired) listings are ever shown.
  const activeListings = useMemo(
    () => listings.filter((item) => !isExpired(item, now)),
    [listings, now]
  );

  const handleCreateListing = (e) => {
    e.preventDefault();

    if (isPerishable && !expiresAt) {
      alert('An expiration date and time is required for perishable items.');
      return;
    }

    const newListing = {
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      title: title.trim(),
      price: Number(price),
      isPerishable,
      expiresAt: isPerishable ? new Date(expiresAt).getTime() : null,
      createdAt: Date.now()
    };

    setListings((prev) => [newListing, ...prev]);
    alert('Listing saved.');

    setTitle('');
    setPrice('');
    setIsPerishable(false);
    setExpiresAt('');
  };

  // Remove every listing. The empty list is persisted, so the samples are
  // not reseeded on the next reload.
  const handleClearAll = () => {
    if (listings.length === 0) return;
    if (!window.confirm('Remove all listings? This cannot be undone.')) return;
    setListings([]);
  };

  // Bring the sample listings back, keeping anything already posted.
  // Samples that are already expired are skipped, so they do not flash up.
  const handleRestoreSamples = () => {
    const now = Date.now();
    setListings((prev) => {
      const existingIds = new Set(prev.map((item) => item.id));
      const missingSamples = buildSeedListings(now).filter(
        (item) => !existingIds.has(item.id) && !isExpired(item, now)
      );
      return [...prev, ...missingSamples];
    });
  };

  return (
    <div style={{ padding: '20px', fontFamily: 'Arial, sans-serif', maxWidth: '400px', margin: 'auto' }}>
      {/* ---------------- PROFILE HEADER ---------------- */}
      <div style={{ textAlign: 'center', marginBottom: '30px', background: '#f8f9fa', padding: '20px', borderRadius: '10px' }}>
        <div style={{ width: '60px', height: '60px', background: '#0056b3', color: '#fff', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '24px', margin: 'auto', marginBottom: '10px' }}>
          B
        </div>
        <h2 style={{ margin: '5px 0' }}>{user.username}</h2>
        <p style={{ color: '#666', margin: '0' }}>{user.email}</p>
      </div>

      {/* ---------------- LISTING FORM ---------------- */}
      <form onSubmit={handleCreateListing} style={{ background: '#fff', padding: '15px', borderRadius: '10px', boxShadow: '0 2px 5px rgba(0,0,0,0.1)' }}>
        <h3>Post a Listing</h3>

        <div style={{ marginBottom: '10px' }}>
          <label style={{ display: 'block', fontSize: '14px', marginBottom: '5px' }}>Listing title:</label>
          <input
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="For example: Fish / Fruit"
            required
            style={{ width: '100%', padding: '8px', boxSizing: 'border-box' }}
          />
        </div>

        <div style={{ marginBottom: '10px' }}>
          <label style={{ display: 'block', fontSize: '14px', marginBottom: '5px' }}>Price (PHP):</label>
          <input
            type="number"
            value={price}
            onChange={(e) => setPrice(e.target.value)}
            placeholder="500"
            min="0"
            step="0.01"
            required
            style={{ width: '100%', padding: '8px', boxSizing: 'border-box' }}
          />
        </div>

        <div style={{ marginBottom: '10px', display: 'flex', alignItems: 'center', gap: '10px' }}>
          <input
            type="checkbox"
            id="perishableCheck"
            checked={isPerishable}
            onChange={(e) => setIsPerishable(e.target.checked)}
          />
          <label htmlFor="perishableCheck" style={{ fontSize: '14px' }}>Is this perishable?</label>
        </div>

        {/* Required date and time picker for perishable items */}
        {isPerishable && (
          <div style={{ marginBottom: '15px', background: '#fff3cd', padding: '10px', borderRadius: '5px' }}>
            <label style={{ display: 'block', fontSize: '14px', marginBottom: '5px', color: '#856404' }}>
              <strong>Expiration date and time (required):</strong>
            </label>
            <input
              type="datetime-local"
              value={expiresAt}
              onChange={(e) => setExpiresAt(e.target.value)}
              required={isPerishable}
              style={{ width: '100%', padding: '8px', boxSizing: 'border-box' }}
            />
            <small style={{ color: '#856404', fontSize: '12px' }}>
              The listing is removed automatically when this time is reached.
            </small>
          </div>
        )}

        <button type="submit" style={{ width: '100%', padding: '10px', background: '#0056b3', color: '#fff', border: 'none', borderRadius: '5px', cursor: 'pointer', fontWeight: 'bold' }}>
          Post Listing
        </button>
      </form>

      {/* ---------------- ACTIVE LISTINGS ---------------- */}
      <div style={{ marginTop: '25px' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '10px', marginBottom: '10px' }}>
          <h3 style={{ margin: '0' }}>Active listings ({activeListings.length})</h3>
          <div style={{ display: 'flex', gap: '6px' }}>
            <button
              type="button"
              onClick={handleRestoreSamples}
              style={{
                padding: '6px 10px',
                background: 'transparent',
                color: '#0056b3',
                border: '1px solid #0056b3',
                borderRadius: '5px',
                fontSize: '12px',
                fontWeight: 'bold',
                cursor: 'pointer'
              }}
            >
              Restore samples
            </button>
            <button
              type="button"
              onClick={handleClearAll}
              disabled={listings.length === 0}
              style={{
                padding: '6px 10px',
                background: 'transparent',
                color: listings.length === 0 ? '#bbb' : '#c62828',
                border: `1px solid ${listings.length === 0 ? '#ddd' : '#c62828'}`,
                borderRadius: '5px',
                fontSize: '12px',
                fontWeight: 'bold',
                cursor: listings.length === 0 ? 'not-allowed' : 'pointer'
              }}
            >
              Clear all
            </button>
          </div>
        </div>

        {activeListings.length === 0 ? (
          <p style={{ color: '#777', fontSize: '14px' }}>No active listings yet.</p>
        ) : (
          <ul style={{ listStyle: 'none', padding: '0', margin: '0', display: 'grid', gap: '8px' }}>
            {activeListings.map((item) => (
              <li
                key={item.id}
                style={{ background: '#fff', padding: '12px', borderRadius: '10px', boxShadow: '0 2px 5px rgba(0,0,0,0.1)' }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '10px' }}>
                  <strong style={{ fontSize: '15px' }}>{item.title}</strong>
                  <span style={{ color: '#0056b3', fontWeight: 'bold' }}>₱{item.price.toFixed(2)}</span>
                </div>
                {item.isPerishable && item.expiresAt && (
                  <div style={{ marginTop: '6px', fontSize: '12px', color: '#856404' }}>
                    Expires {new Date(item.expiresAt).toLocaleString()}
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* ---------------- FOOTER ---------------- */}
      <div style={{ textAlign: 'center', marginTop: '30px', fontSize: '12px', color: '#777' }}>
        <div>Camiguin Market · Buy Local. Support Local!</div>
        <div>v0.2 (2)</div>
        <div style={{ marginTop: '6px', fontWeight: 'bold', color: '#333', fontSize: '13px' }}>
          {user.fullName}
        </div>
      </div>
    </div>
  );
}
