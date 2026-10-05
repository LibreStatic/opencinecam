# Google Play closed test (production gate)

The Play developer account is a personal account created after 13 November 2023. Before Play Console unlocks the Production track, it requires:

1. a published closed-testing release;
2. at least **12 testers opted in** to that closed test;
3. those testers opted in **continuously for 14 days**;
4. an application for production access on the Dashboard. It asks about the test, the feedback received and production readiness, and Google answers in about 7 days.

Google can ask for more testing if engagement was low. Testers who opt in but never open the app do not help, and neither does a tester who leaves before day 14. Recruit 20 to 25 to keep a margin above 12.

## Track

- Track: **Closed testing - Alpha** (it already exists, empty).
- Countries: all countries where the paid app is sold.
- Feedback channel (shown on the opt-in page): https://github.com/LibreStatic/opencinecam/issues
- Builds: `tools/build-play-bundle.sh` produces the AAB signed with the upload key. The first upload enrols the app in Play App Signing.

## Who can join

Pick one access method on *Closed testing > Manage track > Testers*:

| Method | How it works | When to use it |
|---|---|---|
| Email list | Add each Gmail address in the Console (*Create email list*, paste comma-separated or upload a CSV) | You know every tester and want full control |
| Google Group (recommended) | Testers join `opencinecam-testers@googlegroups.com` themselves (or you approve requests). Everyone in the group can opt in | Recruiting in public: post the group and opt-in links, no Console edits per tester |

There is no "first N people" cap for closed tracks. The way to limit the pool is to make the group approval-only, or to stop adding addresses. Open testing does have a tester cap, but its minimum is 1,000 and it does not count toward the 12-tester requirement.

Each tester then:

1. joins the group (if using Groups);
2. opens the opt-in link (`https://play.google.com/apps/testing/com.librestatic.opencinecam`) with the same Google account and taps *Become a tester*;
3. installs from the Play link on the opt-in page;
4. keeps the app installed and stays opted in for at least 14 days.

## Paid app and testers

Play's rule: **testers must purchase paid apps in open and closed tests.** Only the *internal* track installs paid apps for free, and internal testers do not count toward the 12.

To avoid making testers pay:

- **Promo codes (recommended).** *Monetize with Play > Promo codes* creates up to 500 paid-app codes per quarter. Send one code per accepted tester. They redeem it in the Play Store (*Redeem code*) and the app becomes owned on that account, so the purchase also carries over to production. Codes are tied to the app, not the track. Confirm with one code that redemption works while the app is only in closed testing.
- **Testers pay.** This is the most friction and it works against reaching 12. Avoid it.
- License testers (*Settings > License testing*) affect in-app billing tests only; they do not make the paid app free.

The price cannot change from paid to free later. Keep it paid.

## Checklist

- [ ] Data safety form saved (no data collected or shared)
- [ ] Store listing: screenshots refreshed for the current UI in all locales
- [ ] AAB uploaded to Closed testing - Alpha with release notes
- [ ] Testers configured (Google Group) and feedback URL set
- [ ] Release sent for review and approved
- [ ] Promo codes generated (25 to start)
- [ ] 12+ testers opted in. Day 0 = the day the 12th tester opts in.
- [ ] Day 14+: apply for production access from the Dashboard
