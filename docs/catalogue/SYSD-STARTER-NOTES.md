# Stuff You Should Do: starter catalogue notes (DRAFT)

Draft for Tim and Gareth to edit. Not final content. The data is in `sysd-starter.json` (58 items).

## Fields

| Field | Meaning |
|---|---|
| `id` | Stable kebab-case id, prefixed with the category id. Do not rename once shipped. |
| `category` | Onboarding category: `home`, `renter`, `car`, `kids`, `pets`, `health`, `money`, `digital`. |
| `title` | Short imperative title, Australian English, under 40 characters. |
| `why` | One plain sentence on why it matters, under 120 characters. |
| `interval` | `{"every": n, "unit": "DAY" \| "WEEK" \| "MONTH" \| "YEAR"}`. |
| `repeatFrom` | `COMPLETION`: next one counts from when you last did it, so overdue items don't stack. `DUE_DATE`: fixed calendar date (renewals, tax time). |
| `suggestedMonth` | 1 to 12 for yearly `DUE_DATE` items with a known national month, otherwise `null`. |
| `suggestedTime` | Default local time, `HH:MM` 24h. 09:00 for jobs around the house, 10:00 for booking appointments, 18:00 for pet treatments, 19:00 to 20:00 for admin and digital. |
| `alertLevel` | `STANDARD` for everything except car rego, which is `CRITICAL`. |
| `regions` | `["AU"]` for Australia-specific items, `["*"]` for universal. |
| `stateVaries` | `true` where rules or intervals differ by state or territory. In these items the `why` tells people to check their state's rules and states no law. |
| `health` | `true` for health items, which are never sponsored. This covers all `health` items plus three kids items: `kids-health-check`, `kids-dentist` and `kids-immunisation-check`. |
| `link` | Always `null` in v1. |
| `confidence` | `high` / `medium` / `low`: how sure we are that the interval is a sensible general default. |

## Items per category

| Category | Count |
|---|---|
| home | 10 |
| renter | 6 |
| car | 7 |
| kids | 7 |
| pets | 6 |
| health | 7 |
| money | 9 |
| digital | 6 |
| **Total** | **58** |

`home` and `money` are over the 5 to 8 target. To trim, the easiest cuts are `home-roof-check`, `home-dryer-vent-clean` and `money-energy-plan-compare`.

## Assumptions

- **No real date for yearly renewals yet.** Several yearly `DUE_DATE` items depend on the user's own date, not a national one. These have `suggestedMonth: null`: `renter-lease-end-check`, `renter-contents-insurance`, `car-rego-renewal`, `car-insurance-renewal`, `pets-council-registration`, `money-insurance-renewals` and `money-health-insurance-review`. Onboarding should ask for the date, or default to "today plus one interval".
- **Fixed months.** Only three items have one:
  - Tax return in July (`7`). The ATO suggests waiting until late July for pre-fill.
  - Flu vaccine in April (`4`).
  - School forms in January (`1`).
- **"Check the expiry" items.** Driver's licence, passport and smoke alarm expiry are yearly `COMPLETION` checks of the expiry date, not the renewal itself. Renewal periods vary (licences vary by state, passports by age), so a yearly check is safer than guessing.
- **Car service.** Set to yearly, but most logbooks also give a kilometre interval. Do has no odometer, so `why` says "follow your logbook".
- **Health wording.** Every health interval is a reminder to check or book, not advice. Skin check, eye test and GP check-up all say "ask your GP / optometrist how often". No age- or sex-specific screening is included.
- **Renter items stay neutral on who is responsible.** That applies to smoke alarms, air-con filters and similar jobs, because tenant and landlord duties vary by state.
- **`CRITICAL` is used once, for car rego.** `money-drivers-licence` could also qualify, but it is a yearly expiry check rather than the deadline itself, so it is `STANDARD`. Up to Tim.
- **Perth/WA note.** `money-energy-plan-compare` is marked `stateVaries`, because household electricity retail choice is limited in much of WA. Consider hiding it for WA users.

## Items I am unsure about

**Low confidence (19).** The interval is a reasonable guess, not a cited recommendation:

- `home-hot-water-check`: yearly. The manufacturer schedule differs by type (electric, gas, solar, heat pump). Some makers say to operate the relief valve more often.
- `home-dryer-vent-clean`: yearly, unsourced.
- `home-roof-check`: yearly, unsourced.
- `renter-inspection-prep`: 3 months. The real frequency is set by state law and the agent. It might work better as a one-off reminder keyed to the inspection notice.
- `renter-repairs-list`: 3 months, unsourced.
- `renter-condition-photos`: yearly, unsourced.
- `kids-health-check`: yearly. Child health check schedules depend on age, so the item defers to the GP or child health nurse.
- `kids-immunisation-check`: yearly. The schedule depends on age, so the item defers to the GP.
- `kids-school-forms`: January. School year timing differs slightly by state.
- `kids-emergency-contacts`: 6 months, unsourced.
- `kids-car-seat-check`: 3 months. Guidelines say "regularly" without a number.
- `kids-clothes-shoes-sizes`: 6 months, unsourced.
- `health-skin-check`: yearly placeholder. The item tells people to ask their GP.
- `health-eye-test`: 2 years placeholder. The item tells people to ask their optometrist.
- `health-prescriptions-check`: 3 months, unsourced.
- `money-will-review`: 3 years, unsourced. It is mainly triggered by life events.
- `digital-password-check`: 6 months. Current guidance favours changing passwords only when they are reused or breached. The item says that.
- `digital-app-permissions`: 6 months, unsourced.
- `digital-account-recovery`: yearly, unsourced.

**Medium confidence.** These are reasonable common defaults, but I didn't find a strong single source:

- Gutters: 6 months.
- Air-con filters: 3 months. It varies by unit and manual.
- Air-con service: yearly.
- Tyre rotation: 6 months. Often given in km.
- Wipers: yearly.
- Fluids: monthly.
- Pet worming: 3 months. Vet sources commonly say this for adult dogs, but puppies differ.
- Flea and tick: monthly. It depends on the product, and some last 3 to 12 months.
- Pet vaccinations: yearly check. Some boosters are every 3 years, so the vet sets the date.
- Microchip and registration details: yearly.
- Subscriptions, super, insurance and energy plan reviews.

**stateVaries (12):**

- `home-smoke-alarm-test`
- `home-smoke-alarm-expiry`
- `home-safety-switch-test`
- `renter-inspection-prep`
- `renter-lease-end-check`
- `renter-smoke-alarm-test`
- `car-rego-renewal`
- `kids-school-forms`
- `pets-microchip-details`
- `pets-council-registration`
- `money-drivers-licence`
- `money-energy-plan-compare`

`home-safety-switch-test` is set to 3 months, which matches QLD, WA, Tas and Vic guidance. NSW guidance says at least every 6 months.

**Other open questions:**

- Should `renter-smoke-alarm-test` exist at all, given it duplicates the home item? It is kept because renters pick `renter`, not `home`.
- `money-health-insurance-review` is AU-specific (private health insurance). It might belong in `health`, but `health` items are never sponsored, and insurance might be.

## Sources

Checked by web search on 27 November 2026. Every item not listed here is unsourced and its interval is a judgement call.

- **Smoke alarms (test monthly, replace at 10 years, vacuum regularly):**
  - Fire and Rescue NSW: https://www.fire.nsw.gov.au/fire-safety/home-fire-safety/smoke-alarms/smoke-alarm-maintenance-guide
  - ACT ESA: https://esa.act.gov.au/be-emergency-ready/smoke-alarms
  - WA Government: https://www.wa.gov.au/government/announcements/it-your-smoke-alarms-10-year-anniversary
  - Fire Rescue Victoria: https://www.frv.vic.gov.au/smoke-alarms
- **Safety switch (RCD) testing:**
  - WA Building and Energy, 3 months: https://www.wa.gov.au/government/multi-step-guides/electrical-safety-home/rcd-safety-switches
  - QLD Electrical Safety Office, 3 months: https://electricalsafety.qld.gov.au/electrical-safety-home/safety-switches
  - CBOS Tasmania: https://cbos.tas.gov.au/safetyswitches/homes
  - Energy Safe Victoria: https://www.energysafe.vic.gov.au/test-reset-protect-0
  - NSW Government, at least 6 months: https://www.nsw.gov.au/housing-and-construction/safety-home/electrical-safety/safety-switches-protect-you
- **Termite inspection at least every 12 months:**
  - NT Government: https://www.nt.gov.au/property/building-and-development/termite-protection
  - QBCC: https://www.qbcc.qld.gov.au/sites/default/files/documents/publication-termite-management-systems-homeowners-builders.pdf
  - AEPMA notes on AS 3660.2: https://aepma.com.au/News/32028/AEPMA_notes_on_updates_to_AS_3660-2_2017
- **Tyre pressure at least monthly, check cold, include the spare:**
  - RACQ: https://www.racq.com.au/articles/car-maintenance/how-to-check-your-car-tyre-pressure
  - RACV: https://www.racv.com.au/royalauto/transport/cars/how-to-check-your-car-tyre-pressure-and-recommended-pressure.html
- **Child car restraints, check regularly:**
  - Kidsafe consumer guide: https://kidsafe.com.au/wp-content/uploads/2021/03/2020-Detailed-Consumer-Guide-FINALv2.pdf
  - Better Health Channel: https://www.betterhealth.vic.gov.au/health/HealthyLiving/child-safety-in-the-car
- **Pet worming, flea and tick (worm regularly, follow your vet and the product label):**
  - RSPCA NSW: https://rspcansw.org.au/blog/animal-care-information/worming-flea-treatments
  - University of Sydney Vet Hospital: https://www.sydney.edu.au/vet-hospital/small-animals/dogs/preventative-health/parasites.html
- **Dental check-up every 6 to 12 months, the dentist sets the interval:**
  - healthdirect: https://www.healthdirect.gov.au/dental-check-up
- **Flu vaccine every year, around April to May:**
  - Department of Health: https://www.health.gov.au/influenza-vaccination
  - healthdirect: https://www.healthdirect.gov.au/flu-vaccine-faqs
- **Tax return (1 July to 31 October for self-lodgers):**
  - ATO: https://www.ato.gov.au/individuals-and-families/your-tax-return/before-you-prepare-your-tax-return/preparing-your-tax-return
- **Passport (adult validity up to 10 years):**
  - DFAT passport policy: https://www.dfat.gov.au/about-us/publications/corporate/passports/passport-policy
  - I did not find an official source for the "six months' validity" travel rule, so the `why` says only "some countries need months left".
