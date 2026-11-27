// Resolves a sign-in email to its Firebase Auth uid and reads or writes the
// admins/{uid} marker that grants the admin role. Run by grant-admin.yml with
// the project's service account (FIREBASE_SERVICE_ACCOUNT); the credential is
// never printed.
import { writeFileSync } from 'node:fs';
import { initializeApp, cert } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';

const mask = (email) => {
  const [local, domain] = email.split('@');
  return `${local.slice(0, 2)}***@${domain}`;
};

const email = (process.env.EMAIL || '').trim().toLowerCase();
const mode = (process.env.MODE || 'check').trim();
const result = { mode, email: email ? mask(email) : null };

if (!email) {
  result.error = 'EMAIL is empty';
} else {
  const app = initializeApp({
    credential: cert(JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT)),
  });
  const auth = getAuth(app);
  const db = getFirestore(app);
  try {
    const user = await auth.getUserByEmail(email);
    const ref = db.doc(`admins/${user.uid}`);
    result.uid = user.uid;
    result.displayName = user.displayName || null;
    result.providers = (user.providerData || []).map((p) => p.providerId);
    result.disabled = user.disabled === true;
    result.lastSignIn = user.metadata?.lastSignInTime || null;
    result.existedBefore = (await ref.get()).exists;

    if (mode === 'grant') {
      await ref.set(
        {
          active: true,
          grantedAt: FieldValue.serverTimestamp(),
          grantedBy: 'grant-admin workflow',
        },
        { merge: true }
      );
      const snap = await ref.get();
      result.adminDoc = snap.exists ? snap.data() : null;
      result.granted = snap.exists && snap.get('active') === true;
    } else if (mode === 'revoke') {
      await ref.delete();
      result.revoked = !(await ref.get()).exists;
    }
  } catch (e) {
    result.error = `${e.code || e.name}: ${e.message}`;
  }
}

console.log(JSON.stringify(result, null, 2));
writeFileSync('admin-result.json', JSON.stringify(result, null, 2));
if (result.error) process.exitCode = 1;
