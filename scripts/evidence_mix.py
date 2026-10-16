"""Offline mix comparison; selection is separated only after readiness checks.

All non-selection fields, including unknown sends and readability, remain exact.
Historical executed policies under runs/ are frozen evidence, not imports.
"""
from copy import deepcopy


def compare_mix(before, after):
    def split(rows):
        musical = deepcopy(rows)
        selection = []
        for row in musical:
            aggregate = row['observationsComplete']
            if not isinstance(aggregate, bool) or not all(isinstance(value, bool) for value in row['readable'].values()):
                raise ValueError('Native readiness observations must be booleans')
            if aggregate != (all(row['readable'].values()) and row['sendObservationsComplete']):
                raise ValueError('Inconsistent native aggregate readiness')
            selected = row.pop('selected')
            selected_readable = row['readable'].pop('selected')
            if selected_readable != (selected is not None):
                raise ValueError('Inconsistent selection readiness')
            row.pop('observationsComplete')
            selection.append({'trackId': row['trackId'], 'selected': selected,
                              'readable': selected_readable, 'observationsComplete': aggregate})
        return musical, selection
    a, sa = split(before)
    b, sb = split(after)
    return {'musicalEqual': a == b, 'selectionAndAggregateEqual': sa == sb,
            'excludedPaths': ['mix/*/selected', 'mix/*/readable/selected', 'mix/*/observationsComplete'],
            'aggregateIdentityVerified': True,
            'aggregateRule': 'all(readable.values()) AND sendObservationsComplete, checked before separation',
            'beforeSelectionAndAggregate': sa, 'afterSelectionAndAggregate': sb,
            'scope': 'All other raw fields including every non-selection readability flag, sends and sendObservationsComplete compare exactly'}
