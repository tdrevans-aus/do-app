#!/usr/bin/env python3
"""One-off conversion of docs/catalogue/sysd-starter.json into the Spec 11 asset.

Run from the repository root:

    python3 docs/catalogue/convert_sysd.py

It writes app/src/main/assets/catalogue/sysd.json. The editorial choices
(priority, state rules, hidden states, renewal dates, related items and
keywords) live in the tables below, taken from SYSD-STARTER-NOTES.md. Edit the
tables and run it again; the asset is committed.
"""

import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs" / "catalogue" / "sysd-starter.json"
TARGET = ROOT / "app" / "src" / "main" / "assets" / "catalogue" / "sysd.json"

SCHEMA = 1
CATALOGUE_VERSION = 1

# The starter file uses today's stored names; the asset uses Do's names.
ALERT_NAMES = {"STANDARD": "NORMAL", "CRITICAL": "LOUD", "PROMINENT": "ALARM"}

# The seven yearly renewals whose date belongs to the user (starter notes, Assumptions).
NEEDS_DATE = {
    "renter-lease-end-check",
    "renter-contents-insurance",
    "car-rego-renewal",
    "car-insurance-renewal",
    "pets-council-registration",
    "money-insurance-renewals",
    "money-health-insurance-review",
}

# Starter notes: NSW guidance says test safety switches at least every 6 months.
STATE_RULES = {
    "home-safety-switch-test": {
        "AU-NSW": {
            "interval": {"every": 6, "unit": "MONTH"},
            "why": "Pressing the test button confirms the switch will cut power in a fault. NSW guidance says at least every 6 months.",
        },
    },
}

# Starter notes: household electricity retail choice is limited in much of WA.
HIDDEN_IN = {
    "money-energy-plan-compare": ["AU-WA"],
}

# 0 to 100. High-confidence, common items higher; low-confidence items lower.
PRIORITY = {
    "home-smoke-alarm-test": 95,
    "home-smoke-alarm-expiry": 80,
    "home-safety-switch-test": 88,
    "home-gutters-clean": 64,
    "home-aircon-filter-clean": 60,
    "home-aircon-service": 52,
    "home-hot-water-check": 40,
    "home-termite-inspection": 72,
    "home-dryer-vent-clean": 34,
    "home-roof-check": 36,
    "renter-inspection-prep": 44,
    "renter-lease-end-check": 70,
    "renter-contents-insurance": 58,
    "renter-smoke-alarm-test": 90,
    "renter-repairs-list": 42,
    "renter-condition-photos": 38,
    "car-rego-renewal": 93,
    "car-service": 78,
    "car-tyre-pressure": 84,
    "car-tyre-rotation": 58,
    "car-wiper-blades": 50,
    "car-fluids-check": 56,
    "car-insurance-renewal": 66,
    "kids-health-check": 46,
    "kids-dentist": 74,
    "kids-immunisation-check": 45,
    "kids-school-forms": 48,
    "kids-emergency-contacts": 41,
    "kids-car-seat-check": 47,
    "kids-clothes-shoes-sizes": 35,
    "pets-worming": 68,
    "pets-flea-tick": 67,
    "pets-vaccinations": 63,
    "pets-vet-check": 85,
    "pets-microchip-details": 54,
    "pets-council-registration": 60,
    "health-dentist": 89,
    "health-skin-check": 45,
    "health-eye-test": 43,
    "health-gp-checkup": 65,
    "health-flu-vaccine": 86,
    "health-prescriptions-check": 39,
    "health-first-aid-kit": 52,
    "money-tax-return": 91,
    "money-insurance-renewals": 57,
    "money-health-insurance-review": 59,
    "money-drivers-licence": 62,
    "money-passport-expiry": 64,
    "money-subscriptions-review": 63,
    "money-super-check": 61,
    "money-will-review": 37,
    "money-energy-plan-compare": 55,
    "digital-password-check": 44,
    "digital-phone-backup": 76,
    "digital-computer-backup": 62,
    "digital-update-devices": 66,
    "digital-app-permissions": 36,
    "digital-account-recovery": 42,
}

# Up to three related items each, best first. Every id must exist.
RELATED = {
    "home-smoke-alarm-test": ["home-smoke-alarm-expiry", "home-safety-switch-test"],
    "home-smoke-alarm-expiry": ["home-smoke-alarm-test"],
    "home-safety-switch-test": ["home-smoke-alarm-test"],
    "home-gutters-clean": ["home-roof-check"],
    "home-aircon-filter-clean": ["home-aircon-service"],
    "home-aircon-service": ["home-aircon-filter-clean"],
    "home-hot-water-check": ["home-safety-switch-test"],
    "home-termite-inspection": ["home-roof-check"],
    "home-dryer-vent-clean": ["home-aircon-filter-clean"],
    "home-roof-check": ["home-gutters-clean"],
    "renter-inspection-prep": ["renter-repairs-list", "renter-condition-photos"],
    "renter-lease-end-check": ["renter-condition-photos", "renter-contents-insurance"],
    "renter-contents-insurance": ["money-insurance-renewals"],
    "renter-smoke-alarm-test": ["renter-repairs-list"],
    "renter-repairs-list": ["renter-inspection-prep"],
    "renter-condition-photos": ["renter-lease-end-check"],
    "car-rego-renewal": ["car-insurance-renewal", "money-drivers-licence"],
    "car-service": ["car-tyre-rotation", "car-rego-renewal"],
    "car-tyre-pressure": ["car-tyre-rotation", "car-fluids-check"],
    "car-tyre-rotation": ["car-tyre-pressure"],
    "car-wiper-blades": ["car-fluids-check"],
    "car-fluids-check": ["car-tyre-pressure", "car-wiper-blades"],
    "car-insurance-renewal": ["car-rego-renewal"],
    "kids-health-check": ["kids-immunisation-check", "kids-dentist"],
    "kids-dentist": ["kids-health-check"],
    "kids-immunisation-check": ["kids-health-check"],
    "kids-school-forms": ["kids-emergency-contacts"],
    "kids-emergency-contacts": ["kids-school-forms"],
    "kids-car-seat-check": ["kids-clothes-shoes-sizes"],
    "kids-clothes-shoes-sizes": ["kids-car-seat-check"],
    "pets-worming": ["pets-flea-tick"],
    "pets-flea-tick": ["pets-worming"],
    "pets-vaccinations": ["pets-vet-check"],
    "pets-vet-check": ["pets-vaccinations", "pets-worming"],
    "pets-microchip-details": ["pets-council-registration"],
    "pets-council-registration": ["pets-microchip-details"],
    "health-dentist": ["health-gp-checkup"],
    "health-skin-check": ["health-gp-checkup"],
    "health-eye-test": ["health-gp-checkup"],
    "health-gp-checkup": ["health-prescriptions-check", "health-skin-check"],
    "health-flu-vaccine": ["health-gp-checkup"],
    "health-prescriptions-check": ["health-first-aid-kit"],
    "health-first-aid-kit": ["health-prescriptions-check"],
    "money-tax-return": ["money-super-check"],
    "money-insurance-renewals": ["money-health-insurance-review"],
    "money-health-insurance-review": ["money-insurance-renewals"],
    "money-drivers-licence": ["money-passport-expiry"],
    "money-passport-expiry": ["money-drivers-licence"],
    "money-subscriptions-review": ["money-energy-plan-compare"],
    "money-super-check": ["money-tax-return"],
    "money-will-review": ["money-super-check"],
    "money-energy-plan-compare": ["money-subscriptions-review"],
    "digital-password-check": ["digital-account-recovery"],
    "digital-phone-backup": ["digital-computer-backup"],
    "digital-computer-backup": ["digital-phone-backup"],
    "digital-update-devices": ["digital-app-permissions"],
    "digital-app-permissions": ["digital-password-check"],
    "digital-account-recovery": ["digital-password-check"],
}

# Lower case phrases matched against what someone types in Add.
KEYWORDS = {
    "home-smoke-alarm-test": ["smoke alarm", "smoke detector", "fire alarm"],
    "home-smoke-alarm-expiry": ["smoke alarm expiry", "replace smoke alarm"],
    "home-safety-switch-test": ["safety switch", "rcd", "circuit breaker"],
    "home-gutters-clean": ["clean the gutters", "gutters"],
    "home-aircon-filter-clean": ["aircon filter", "air-con filter", "air conditioner filter"],
    "home-aircon-service": ["aircon service", "air-con service", "air conditioner service"],
    "home-hot-water-check": ["hot water", "hot water system"],
    "home-termite-inspection": ["termite", "termites", "pest inspection"],
    "home-dryer-vent-clean": ["dryer vent", "dryer duct", "dryer lint"],
    "home-roof-check": ["roof check", "roof tiles", "roof leak"],
    "renter-inspection-prep": ["rental inspection", "routine inspection", "house inspection"],
    "renter-lease-end-check": ["lease ends", "end of lease", "lease renewal"],
    "renter-contents-insurance": ["contents insurance"],
    "renter-smoke-alarm-test": ["smoke alarm rental"],
    "renter-repairs-list": ["repairs list", "rental repairs", "property manager"],
    "renter-condition-photos": ["condition report", "rental photos"],
    "car-rego-renewal": ["rego", "car rego", "car registration"],
    "car-service": ["car service", "service the car", "logbook service"],
    "car-tyre-pressure": ["tyre pressure", "tyres", "tire pressure"],
    "car-tyre-rotation": ["tyre rotation", "rotate tyres", "tyre tread"],
    "car-wiper-blades": ["wipers", "wiper blades"],
    "car-fluids-check": ["engine oil", "oil change", "coolant", "washer fluid"],
    "car-insurance-renewal": ["car insurance"],
    "kids-health-check": ["kids health check", "child health check"],
    "kids-dentist": ["kids dentist", "kids dental"],
    "kids-immunisation-check": ["immunisation", "immunisations", "kids vaccinations"],
    "kids-school-forms": ["school forms", "permission slip", "enrolment forms"],
    "kids-emergency-contacts": ["emergency contacts"],
    "kids-car-seat-check": ["car seat", "booster seat"],
    "kids-clothes-shoes-sizes": ["shoe size", "school shoes", "clothes sizes"],
    "pets-worming": ["worming", "worm the dog", "worm the cat", "worming tablet"],
    "pets-flea-tick": ["flea treatment", "flea and tick", "tick treatment", "fleas"],
    "pets-vaccinations": ["pet vaccination", "dog vaccination", "cat vaccination"],
    "pets-vet-check": ["vet check", "vet appointment", "the vet"],
    "pets-microchip-details": ["microchip"],
    "pets-council-registration": ["pet registration", "dog registration", "cat registration"],
    "health-dentist": ["dentist", "dental"],
    "health-skin-check": ["skin check"],
    "health-eye-test": ["eye test", "optometrist", "new glasses"],
    "health-gp-checkup": ["gp appointment", "gp check-up", "doctor appointment", "health check-up"],
    "health-flu-vaccine": ["flu shot", "flu vaccine"],
    "health-prescriptions-check": ["prescription", "prescriptions", "repeat script"],
    "health-first-aid-kit": ["first aid", "first aid kit"],
    "money-tax-return": ["tax return", "lodge tax", "ato"],
    "money-insurance-renewals": ["insurance renewal", "home insurance", "insurance renewals"],
    "money-health-insurance-review": ["health insurance", "private health"],
    "money-drivers-licence": ["drivers licence", "driver's licence", "licence renewal"],
    "money-passport-expiry": ["passport"],
    "money-subscriptions-review": ["subscriptions", "streaming subscriptions", "cancel subscription"],
    "money-super-check": ["superannuation", "super fund", "super balance"],
    "money-will-review": ["make a will", "update my will", "review my will", "update the will"],
    "money-energy-plan-compare": ["energy plan", "electricity plan", "electricity bill", "power bill"],
    "digital-password-check": ["passwords", "password manager", "change passwords"],
    "digital-phone-backup": ["phone backup", "back up phone"],
    "digital-computer-backup": ["computer backup", "back up computer", "laptop backup"],
    "digital-update-devices": ["software update", "update devices"],
    "digital-app-permissions": ["app permissions"],
    "digital-account-recovery": ["account recovery", "two-factor", "2fa"],
}


def convert(starter):
    ids = [item["id"] for item in starter]
    known = set(ids)
    for table in (PRIORITY, RELATED, KEYWORDS):
        missing = known - set(table)
        extra = set(table) - known
        if missing or extra:
            sys.exit(f"table mismatch: missing={sorted(missing)} extra={sorted(extra)}")
    for item_id, related in RELATED.items():
        for other in related:
            if other not in known or other == item_id:
                sys.exit(f"bad related {item_id} -> {other}")
    items = []
    for item in starter:
        item_id = item["id"]
        items.append({
            "id": item_id,
            "version": 1,
            "category": item["category"],
            "title": item["title"],
            "why": item["why"],
            "interval": item["interval"],
            "repeatFrom": item["repeatFrom"],
            "suggestedMonth": item["suggestedMonth"],
            "suggestedTime": item["suggestedTime"],
            "alertLevel": ALERT_NAMES[item["alertLevel"]],
            "regions": item["regions"],
            "stateVaries": item["stateVaries"],
            "stateRules": STATE_RULES.get(item_id, {}),
            "hiddenIn": HIDDEN_IN.get(item_id, []),
            "needsDate": item_id in NEEDS_DATE,
            "priority": PRIORITY[item_id],
            "related": RELATED[item_id],
            "keywords": KEYWORDS[item_id],
            "health": item["health"],
            "link": item["link"],
        })
    return {"schema": SCHEMA, "catalogueVersion": CATALOGUE_VERSION, "items": items}


def main():
    starter = json.loads(SOURCE.read_text(encoding="utf-8"))
    catalogue = convert(starter)
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(json.dumps(catalogue, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    top = sorted(catalogue["items"], key=lambda i: -i["priority"])[:15]
    for rank, item in enumerate(top, 1):
        print(f"{rank:2d}. {item['priority']:3d} {item['id']}: {item['title']}")


if __name__ == "__main__":
    main()
