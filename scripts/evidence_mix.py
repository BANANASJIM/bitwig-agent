"""Offline mix comparison; selection is separated only after readiness checks.

All non-selection fields, including unknown sends and readability, remain exact.
"""

from copy import deepcopy


def compare_mix(before, after):
    def split(rows):
        musical = deepcopy(rows)
        selection = []
        for row in musical:
            aggregate = row["observationsComplete"]
            if not isinstance(aggregate, bool):
                raise ValueError("Native readiness observations must be booleans")
            if aggregate and not row["sendObservationsComplete"]:
                raise ValueError("Inconsistent native aggregate readiness")
            selected = row.pop("selected")
            row.pop("observationsComplete")
            selection.append(
                {
                    "ref": row["ref"],
                    "selected": selected,
                    "observationsComplete": aggregate,
                }
            )
        return musical, selection

    a, sa = split(before)
    b, sb = split(after)
    return {
        "musicalEqual": a == b,
        "selectionAndAggregateEqual": sa == sb,
        "excludedPaths": ["mix/*/selected", "mix/*/observationsComplete"],
        "aggregateIdentityVerified": True,
        "aggregateRule": "observationsComplete implies sendObservationsComplete, checked before separation",
        "beforeSelectionAndAggregate": sa,
        "afterSelectionAndAggregate": sb,
        "scope": "All other raw fields including every unobserved (null) value, sends and sendObservationsComplete compare exactly",
    }
