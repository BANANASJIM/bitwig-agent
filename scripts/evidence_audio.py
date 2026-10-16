"""Offline PCM metrics over immutable consumed bytes; no host or desktop access.

The supplied directory contains music-predeclaration.json and its declared paths.
Scientific dependencies are optional and imported only when analysis is called.
Native render/GUI provenance, guarded execution and result persistence belong to
its caller; historical executed helpers under runs/ remain frozen evidence.
"""
import hashlib
import json
from io import BytesIO
from pathlib import Path


def analyze(out: Path) -> dict:
    """Evaluate declared audio thresholds without operating Bitwig or saving files."""
    import numpy as np
    import soundfile as sf
    from scipy.signal import correlate
    declaration_path = out / 'music-predeclaration.json'
    declaration_bytes = declaration_path.read_bytes()
    declaration = json.loads(declaration_bytes)
    limits = declaration['limits']
    source_bytes = Path(declaration['inputPath']).read_bytes()
    source_hash = hashlib.sha256(source_bytes).hexdigest()
    if source_hash != declaration['inputSha256']:
        raise ValueError('Input changed since predeclaration')
    source, rate = sf.read(BytesIO(source_bytes), always_2d=True, dtype='float64')
    if source.shape[1] != 2 or not np.isfinite(source).all():
        raise ValueError('Finite stereo input required')
    input_peak = float(np.abs(source).max())
    if input_peak > 0.15 or np.any(np.sqrt(np.mean(source * source, axis=0)) < limits['inputRmsMinimum']):
        raise ValueError('Input peak/RMS violates predeclared musical input bounds')
    if np.array_equal(source[:, 0], source[:, 1]):
        raise ValueError('Asymmetric stereo input required')
    cases = {}
    for name, path in declaration['renderPaths'].items():
        metadata = Path(path).stat()
        if metadata.st_mtime_ns < declaration['sealedTimeNs']:
            raise ValueError('Render predates predeclaration')
        audio_bytes = Path(path).read_bytes()
        audio_hash = hashlib.sha256(audio_bytes).hexdigest()
        audio, sample_rate = sf.read(BytesIO(audio_bytes), always_2d=True, dtype='float64')
        if sample_rate != rate or audio.shape[1] != 2 or not np.isfinite(audio).all():
            raise ValueError('Native render format differs or contains non-finite samples')
        channels = []
        for channel in range(2):
            x, y = source[:, channel], audio[:, channel]
            # correlate(y,x)[len(x)-1+lag] = sum x[t]*y[t+lag].
            cross = correlate(y, x, mode='full', method='fft')
            lags = np.arange(-4096, 4097)
            start = np.maximum(0, -lags)
            end = np.minimum(len(x), len(y) - lags)
            count = end - start
            valid = count >= limits['minOverlapFrames']
            if not valid.any():
                raise ValueError('Insufficient actual overlap for lag search')
            lags, start, end, count = (v[valid] for v in (lags, start, end, count))
            def sums(values, lo, hi):
                cumulative = np.concatenate(([0.0], np.cumsum(values)))
                return cumulative[hi] - cumulative[lo]
            sx, sy = sums(x, start, end), sums(y, start + lags, end + lags)
            xx, yy = sums(x*x, start, end), sums(y*y, start + lags, end + lags)
            xy = cross[len(x) - 1 + lags]
            denominator = np.sqrt(np.maximum(0, (xx-sx*sx/count)*(yy-sy*sy/count)))
            correlations = np.divide(xy-sx*sy/count, denominator, out=np.full(len(lags), -np.inf), where=denominator > 0)
            best = int(np.argmax(correlations))
            if not np.isfinite(correlations[best]):
                raise ValueError('Silence/constant signal cannot establish correlation')
            lag = int(lags[best])
            a, b = x[start[best]:end[best]], y[start[best]+lag:end[best]+lag]
            gain = float(np.dot(a, b) / np.dot(a, a))
            predicted = declaration['gains'][name]
            rms = float(np.sqrt(np.mean(b*b)))
            if rms <= 0:
                raise ValueError('Silent output is not musical success')
            residual = float(np.sqrt(np.mean((b-predicted*a)**2)) / rms)
            fitted_residual = float(np.sqrt(np.mean((b-gain*a)**2)) / rms)
            peak = float(np.abs(y).max())
            clipped = int(np.count_nonzero(np.abs(y) >= limits['clippingAbsolute']))
            checks = {'gain': abs(gain-predicted) <= limits['gainAbsoluteError'],
                      'correlation': float(correlations[best]) >= limits['correlationMinimum'],
                      'predictedResidual': residual <= limits['residualRelativeMaximum'], 'noClipping': clipped == 0}
            channels.append({'channel': channel, 'lagSamples': lag, 'searchedSamples': [-4096,4096],
                             'overlapFrames': len(a), 'correlation': float(correlations[best]),
                             'gain': gain, 'predictedGain': predicted, 'residualRelative': residual,
                             'fittedResidualRelative': fitted_residual, 'peak': peak, 'rms': rms,
                             'clippedSamples': clipped, 'checks': checks, 'passed': all(checks.values())})
        cases[name] = {'path': path, 'sha256': audio_hash, 'mtimeNs': metadata.st_mtime_ns,
                       'frames': len(audio), 'channels': channels, 'passed': all(c['passed'] for c in channels)}
    return {'declarationSha256': hashlib.sha256(declaration_bytes).hexdigest(), 'inputSha256': source_hash,
            'inputPeak': input_peak, 'sampleRate': rate, 'cases': cases,
            'passed': all(case['passed'] for case in cases.values()),
            'scope': 'Offline audio metrics only; GUI/native render provenance needs separate evidence'}
