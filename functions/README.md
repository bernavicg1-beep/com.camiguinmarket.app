# Cloud Functions (push notifications)

Nagpadala kini og FCM push kon dunay **bag-ong order**, **kausaban sa status**,
o **bag-ong chat message**. Ang mga token naa sa `users/{uid}/tokens/{token}`
(gina-save sa app), ug ang mga patay nga token gina-delete dayon.

## Setup

```bash
cd functions
npm install
```

Kinahanglan og **Blaze (pay-as-you-go)** plan ang Firebase project kay external
network (FCM) ang gigamit. Ang Firebase CLI mo-prompt og upgrade kon wala pa.

## Deploy

```bash
firebase deploy --only functions
```

O isa ra ka function:

```bash
firebase deploy --only functions:onOrderCreated
```

## Importante

- Ang Admin SDK **molapas sa Firestore rules** — mao kini ang rason nga makabasa
  kini sa mga token sa laing user.
- Kini nga repo wala mag-deploy sa functions; ang CI (build + rules tests) wala
  magkinahanglan niini. Ang pag-deploy gikinahanglan og service account.
