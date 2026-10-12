#!/usr/bin/env python3
"""Make the main menu's sounds (docs/android.md, The main menu): android/app/src/main/res/raw/menu_*.wav.

Every sound is made here from noise and sine waves; nothing is sampled from the game or from anyone's recording. The files are 22050 Hz,
16 bit WAV (the menu's mixer, MenuSound.java, reads them as they are):

  menu_music.wav       40 s stereo loop: a low drone, a cluster of notes a semitone apart, slow breath-like noise, a faint high tone and two
                       deep booms. Every pitch is a whole number of cycles in the 40 s and the noise is made in the frequency domain, so the
                       loop has no seam.
  menu_buzz.wav        4 s mono loop: the hum of a bulb on the mains with the hiss and crackle of a bad contact. The mixer sets its volume
                       from the bulb's brightness, so it is loudest while the light stutters.
  menu_zap_N.wav       short electrical snaps, played when the bulb comes back after a dip.
  menu_pop_N.wav       short soft pops with a falling hum, played as the bulb dips.
  menu_creak_N.wav     wood creaking: floorboards, a door, a low groan of a beam.
  menu_steps_N.wav     footsteps on a wooden floor in another room: approaching, going away, passing, stopping.

Run:  python3 tools/android/menu_sounds.py            (needs numpy and scipy; the output is the same every time)
"""
import sys
from pathlib import Path

import numpy as np
from scipy.io import wavfile
from scipy.signal import fftconvolve, lfilter, butter, sosfilt

SR = 22050
OUT = Path(__file__).resolve().parents[2] / "android" / "app" / "src" / "main" / "res" / "raw"


def rng(seed):
    return np.random.default_rng(seed)


def smooth(x):
    x = np.clip(x, 0.0, 1.0)
    return x * x * (3.0 - 2.0 * x)


def circular(filtered, x):
    """Filter a loop without a seam: filter three copies end to end and keep the middle one."""
    n = len(x)
    return filtered(np.concatenate([x, x, x]))[n:2 * n]


def lowpass(x, cutoff, order=2):
    return sosfilt(butter(order, cutoff / (SR / 2), "low", output="sos"), x)


def highpass(x, cutoff, order=2):
    return sosfilt(butter(order, cutoff / (SR / 2), "high", output="sos"), x)


def bandpass(x, low, high, order=2):
    return sosfilt(butter(order, [low / (SR / 2), high / (SR / 2)], "band", output="sos"), x)


def resonator(x, freq, q):
    """A two pole resonant filter (a ringing body): the signal rings at freq for about q cycles."""
    w = 2 * np.pi * freq / SR
    r = np.exp(-w / (2 * q))
    a = [1.0, -2 * r * np.cos(w), r * r]
    b = [1.0 - r]
    return lfilter(b, a, x)


def reverb(x, seconds=1.3, wet=0.3, seed=1, predelay=0.018):
    """A room: noise that dies away, with a few early reflections, mixed under the dry sound."""
    g = rng(seed)
    n = int(seconds * SR)
    t = np.arange(n) / SR
    ir = g.normal(0, 1, n) * np.exp(-t * 6.9 / seconds)
    ir = lowpass(ir, 3500)
    for delay, gain in ((0.011, 0.5), (0.023, 0.35), (0.037, 0.3), (0.052, 0.2)):
        ir[int(delay * SR)] += gain * 6
    ir = np.concatenate([np.zeros(int(predelay * SR)), ir])
    ir /= np.sqrt(np.sum(ir ** 2))
    tail = fftconvolve(x, ir)[: len(x) + int(seconds * SR * 0.6)]
    dry = np.concatenate([x, np.zeros(len(tail) - len(x))])
    return dry * (1 - wet * 0.5) + tail * wet * 2.2


def normalise(x, peak):
    m = np.max(np.abs(x))
    return x * (peak / m) if m > 0 else x


def trim_tail(x, threshold=0.004):
    idx = np.where(np.abs(x) > threshold * np.max(np.abs(x)))[0]
    return x[: idx[-1] + 1] if len(idx) else x


def fade(x, fade_in=0.004, fade_out=0.03):
    n_in, n_out = int(fade_in * SR), int(fade_out * SR)
    x = x.copy()
    if n_in:
        x[:n_in] *= np.linspace(0, 1, n_in)
    if n_out:
        x[-n_out:] *= np.linspace(1, 0, n_out)
    return x


def save(name, data, peak=0.9):
    data = np.asarray(data, dtype=np.float64)
    data = np.clip(data, -peak, peak) if data.ndim == 1 else np.clip(data, -peak, peak)
    OUT.mkdir(parents=True, exist_ok=True)
    wavfile.write(OUT / name, SR, (data * 32767).astype(np.int16))
    seconds = data.shape[0] / SR
    rms = np.sqrt(np.mean(data ** 2))
    print(f"{name}: {seconds:.2f} s, {data.size * 2 / 1e6:.2f} MB, peak {np.max(np.abs(data)):.2f}, rms {20 * np.log10(rms + 1e-9):.1f} dBFS")


# ---------------------------------------------------------------- music

def periodic_noise(seconds, low, high, seed):
    """Noise with the pitch range [low, high] (a smooth band), made so that it repeats exactly every `seconds`."""
    n = int(seconds * SR)
    g = rng(seed)
    spectrum = np.fft.rfft(g.normal(0, 1, n))
    freqs = np.fft.rfftfreq(n, 1 / SR)
    centre = np.sqrt(low * high)
    width = np.log2(high / low) / 2
    shape = np.exp(-0.5 * ((np.log2(np.maximum(freqs, 1e-3) / centre)) / width) ** 2)
    out = np.fft.irfft(spectrum * shape, n)
    return out / np.sqrt(np.mean(out ** 2))


def make_music():
    T = 40.0
    n = int(T * SR)
    t = np.arange(n) / SR
    two_pi = 2 * np.pi
    left = np.zeros(n)
    right = np.zeros(n)

    def tone(freq, harmonics, phase, sweep_phase, sweep_period, sweep_depth=0.6):
        """A note with a few harmonics whose loudness drifts slowly: the sound opens and closes like a filter."""
        out = np.zeros(n)
        for k, weight in enumerate(harmonics, start=1):
            drift = 1.0 - sweep_depth * (0.5 + 0.5 * np.sin(two_pi * t / sweep_period + sweep_phase + k * 0.9)) * min(1.0, (k - 1) / 3.0)
            out += weight * drift * np.sin(two_pi * freq * k * t + phase * k)
        return out

    # the drone: A1 twice, a hair apart (a half a hertz beat), one more to each side
    harmonics = [1.0, 0.55, 0.34, 0.2, 0.12, 0.07, 0.04, 0.025]
    left += 0.42 * tone(55.0, harmonics, 0.0, 0.0, 20.0)
    right += 0.42 * tone(55.5, harmonics, 1.3, 1.7, 20.0)
    left += 0.14 * tone(55.5, harmonics, 2.1, 3.1, 40.0 / 3)
    right += 0.14 * tone(55.0, harmonics, 0.7, 2.4, 40.0 / 3)
    # a note a semitone above and one at the tritone: the unease
    swell_a = 0.55 + 0.45 * np.sin(two_pi * t / 20.0 + 1.0)
    swell_b = 0.55 + 0.45 * np.sin(two_pi * t / (40.0 / 3) + 4.0)
    left += 0.2 * swell_a ** 2 * tone(58.0, harmonics[:5], 0.4, 0.5, 10.0)
    right += 0.2 * swell_a ** 2 * tone(58.0, harmonics[:5], 1.9, 2.5, 10.0)
    left += 0.16 * swell_b ** 2 * tone(77.0, harmonics[:4], 1.1, 1.0, 8.0)
    right += 0.16 * swell_b ** 2 * tone(77.5, harmonics[:4], 0.2, 3.0, 8.0)
    left += 0.1 * swell_b * tone(82.5, harmonics[:3], 0.6, 2.0, 5.0)
    right += 0.1 * swell_a * tone(82.5, harmonics[:3], 2.6, 0.3, 5.0)
    # under it all, a note too low to hear well, felt more than heard, that shivers slowly
    sub = np.sin(two_pi * 27.5 * t) * (0.7 + 0.3 * np.sin(two_pi * t / 8.0))
    left += 0.3 * sub
    right += 0.3 * sub
    # breath: noise between 150 and 700 Hz that swells and falls away, different in each ear
    for seed, ch, period, phase in ((11, left, 12.0, 0.4), (12, right, 40.0 / 3, 2.0)):
        swell = (0.5 + 0.5 * np.sin(two_pi * t / period + phase)) ** 3
        ch += 0.1 * swell * periodic_noise(T, 150, 700, seed)
    # a faint high tone that comes and goes, with a second a few hertz away so that it shimmers
    window = np.exp(-0.5 * (((t - 29.0 + T / 2) % T - T / 2) / 3.2) ** 2)
    shimmer = np.sin(two_pi * 1760.0 * t) + np.sin(two_pi * 1763.0 * t + 0.8)
    left += 0.012 * window * shimmer
    right += 0.012 * window * np.roll(shimmer, 311)
    window2 = np.exp(-0.5 * (((t - 9.0 + T / 2) % T - T / 2) / 2.2) ** 2)
    left += 0.008 * window2 * np.sin(two_pi * 1318.5 * t)
    right += 0.008 * window2 * np.sin(two_pi * 1320.0 * t + 1.2)
    # two deep booms, far away
    for start, f_from, f_to, level in ((11.0, 62.0, 33.0, 0.5), (34.5, 55.0, 30.0, 0.42)):
        i0 = int(start * SR)
        length = int(5.0 * SR)
        u = np.arange(length) / SR
        phase = two_pi * (f_to * u + (f_from - f_to) * 0.35 * (1 - np.exp(-u / 0.35)))
        env = (1 - np.exp(-u / 0.06)) * np.exp(-u / 1.1)
        boom = level * env * np.sin(phase)
        boom += 0.25 * level * env * np.sin(2 * phase + 0.4)
        left[i0:i0 + length] += boom
        right[i0:i0 + length] += boom * 0.9
    stereo = np.stack([left, right], axis=1)
    # keep the music's bass out of the phone's speaker's way: nothing below 22 Hz
    stereo = np.stack([circular(lambda y: highpass(y, 22.0, 1), stereo[:, 0]), circular(lambda y: highpass(y, 22.0, 1), stereo[:, 1])], axis=1)
    stereo = normalise(stereo, 0.62)
    save("menu_music.wav", stereo)


# ---------------------------------------------------------------- the light

def make_buzz():
    T = 4.0
    n = int(T * SR)
    t = np.arange(n) / SR
    g = rng(21)
    # the hum: 100 Hz (the mains is 50 and a bulb hums at twice that) with its harmonics, the odd ones stronger
    hum = np.zeros(n)
    for k in range(1, 25):
        weight = (k ** -0.85) * (1.0 if k % 2 else 0.6)
        hum += weight * np.sin(2 * np.pi * 100.0 * k * t + g.uniform(0, 2 * np.pi))
    hum += 0.5 * np.sin(2 * np.pi * 50.0 * t + 0.3)
    hum = circular(lambda y: lowpass(y, 5000, 1), hum)
    hum /= np.max(np.abs(hum))
    # hiss that follows the hum's pulses, as a spark does
    hiss = circular(lambda y: bandpass(y, 1500, 7000), g.normal(0, 1, n))
    pulses = 0.35 + 0.65 * np.abs(np.cos(2 * np.pi * 100.0 * t)) ** 3
    hiss = hiss * pulses
    hiss /= np.max(np.abs(hiss))
    # crackle: short bursts of noise at random times, some loud
    crackle = np.zeros(n)
    count = int(T * 17)
    for _ in range(count):
        at = int(g.uniform(0, n))
        length = int(g.uniform(0.002, 0.009) * SR)
        burst = g.normal(0, 1, length) * np.exp(-np.arange(length) / (length / 4.0))
        amp = g.exponential(0.35) + 0.08
        idx = (at + np.arange(length)) % n
        crackle[idx] += burst * amp
    crackle = circular(lambda y: highpass(y, 900, 1), crackle)
    crackle /= np.max(np.abs(crackle))
    # a slow uneven wavering of the whole, so the loop is not a flat tone
    waver = 0.8 + 0.2 * np.sin(2 * np.pi * 0.5 * t + 1.0) * np.sin(2 * np.pi * 0.25 * t + 0.4)
    mix = waver * (0.62 * hum + 0.24 * hiss) + 0.5 * crackle
    save("menu_buzz.wav", normalise(mix, 0.7))


def snap(seed, length, bright, hum_ms, rise):
    g = rng(seed)
    n = int(length * SR)
    t = np.arange(n) / SR
    out = np.zeros(n)
    # the click
    click = g.normal(0, 1, int(0.003 * SR)) * np.exp(-np.arange(int(0.003 * SR)) / (0.0007 * SR))
    out[: len(click)] += 2.2 * click
    # the arc: noise, bright, dying fast, with a few re-strikes
    arc = bandpass(g.normal(0, 1, n), 1200, 3000 + bright * 6000)
    env = np.exp(-t / 0.045)
    for restrike in (0.03, 0.07, 0.11):
        if restrike < length * 0.7:
            env += 0.5 * np.exp(-np.maximum(t - restrike, 0) / 0.02) * (t >= restrike)
    out += 0.9 * arc * env * (1 + 0.5 * np.sin(2 * np.pi * 100 * t))
    # the hum swelling in as the filament takes the current
    hum_env = smooth(t / (hum_ms / 1000.0)) * np.exp(-np.maximum(t - hum_ms / 1000.0, 0) / 0.09)
    hum = sum(np.sin(2 * np.pi * 100 * k * t + k) / k for k in range(1, 9)) * hum_env * rise
    out += 0.7 * hum
    return fade(out, 0.0005, 0.04)


def make_zaps():
    for i, (seed, length, bright, hum_ms, rise) in enumerate(((31, 0.42, 0.9, 120, 1.0), (32, 0.36, 0.6, 90, 0.8), (33, 0.5, 1.0, 160, 1.1)), start=1):
        save(f"menu_zap_{i}.wav", normalise(snap(seed, length, bright, hum_ms, rise), 0.8))


def make_pops():
    for i, (seed, length) in enumerate(((41, 0.26), (42, 0.32)), start=1):
        g = rng(seed)
        n = int(length * SR)
        t = np.arange(n) / SR
        out = np.zeros(n)
        click = g.normal(0, 1, int(0.004 * SR)) * np.exp(-np.arange(int(0.004 * SR)) / (0.001 * SR))
        out[: len(click)] += 1.4 * click
        # the hum falling as the light goes down: from 140 to 55 Hz in a quarter of a second
        f = 55 + 85 * np.exp(-t / 0.08)
        phase = 2 * np.pi * np.cumsum(f) / SR
        whine = sum(np.sin(phase * k) / k for k in range(1, 7)) * np.exp(-t / 0.1)
        out += 0.8 * whine
        out += 0.25 * bandpass(g.normal(0, 1, n), 800, 4000) * np.exp(-t / 0.03)
        save(f"menu_pop_{i}.wav", normalise(fade(out, 0.0005, 0.05), 0.6))


# ---------------------------------------------------------------- wood

def creak(seed, length, f_from, f_to, formants, jitter=0.06, sharp=14.0, stick=0.0):
    """Stick and slip: a pulse train whose rate wanders, rung through a few resonances."""
    g = rng(seed)
    n = int(length * SR)
    t = np.arange(n) / SR
    wander = lowpass(g.normal(0, 1, n), 6.0, 1)
    wander /= np.max(np.abs(wander)) + 1e-9
    bend = smooth(t / length) ** 0.8
    f = (f_from + (f_to - f_from) * bend) * (1 + jitter * wander)
    phase = np.cumsum(f) / SR
    frac = phase % 1.0
    pulses = np.exp(-frac * sharp) * (1 + 0.35 * g.normal(0, 1, n))
    if stick > 0:
        # the board sticks and lets go: the pulses come in bursts
        gate = (lowpass(g.normal(0, 1, n), 9.0, 1) > -stick).astype(float)
        pulses *= lowpass(gate, 60.0, 1)
    body = np.zeros(n)
    for freq, q, weight in formants:
        body += weight * resonator(pulses, freq, q)
    env = smooth(t / (0.2 * length)) * (1 - smooth((t - 0.6 * length) / (0.4 * length))) ** 1.2
    out = body * env * (0.75 + 0.25 * wander)
    return fade(out, 0.01, 0.08)


def make_creaks():
    specs = [
        # a floorboard giving under a foot
        dict(seed=51, length=0.9, f_from=38, f_to=64, formants=[(180, 9, 1.0), (420, 12, 0.7), (760, 10, 0.4)], stick=0.4),
        dict(seed=52, length=1.3, f_from=26, f_to=52, formants=[(150, 8, 1.0), (330, 10, 0.8), (640, 9, 0.45)], stick=0.2),
        # a door on a dry hinge, far off
        dict(seed=53, length=1.7, f_from=82, f_to=150, formants=[(520, 14, 1.0), (1180, 16, 0.8), (1950, 14, 0.4)], sharp=18.0, jitter=0.09),
        dict(seed=54, length=1.1, f_from=120, f_to=70, formants=[(610, 13, 1.0), (1320, 15, 0.7), (2200, 12, 0.3)], sharp=16.0, jitter=0.08),
        # the house settling: a long low groan
        dict(seed=55, length=2.4, f_from=18, f_to=34, formants=[(95, 8, 1.0), (210, 10, 0.8), (430, 9, 0.5)], sharp=10.0, stick=0.0),
        dict(seed=56, length=1.9, f_from=30, f_to=21, formants=[(120, 9, 1.0), (260, 10, 0.7), (540, 10, 0.4)], sharp=11.0, stick=-0.2),
    ]
    for i, spec in enumerate(specs, start=1):
        x = creak(**spec)
        x = reverb(x, 1.1, 0.25, seed=60 + i)
        x = lowpass(x, 3600)
        save(f"menu_creak_{i}.wav", normalise(trim_tail(fade(x, 0.01, 0.12)), 0.8))


def step(seed, weight, brightness):
    """One heavy footstep on a board floor: the thud, the knock, the board's ring and the toe coming down."""
    g = rng(seed)
    n = int(0.9 * SR)
    t = np.arange(n) / SR
    thud = np.sin(2 * np.pi * (52 + 70 * np.exp(-t / 0.035)) * t) * np.exp(-t / 0.07)
    knock = bandpass(g.normal(0, 1, n), 140, 900 + 700 * brightness) * np.exp(-t / 0.022)
    modes = g.choice([108, 142, 176, 214, 262, 318, 396, 470], size=3, replace=False)
    ring = np.zeros(n)
    for m in modes:
        ring += g.uniform(0.3, 0.6) * np.sin(2 * np.pi * m * (1 + g.uniform(-0.01, 0.01)) * t + g.uniform(0, 6.28)) * np.exp(-t / g.uniform(0.12, 0.3))
    heel = 1.0 * thud + 0.8 * knock + 0.4 * ring
    # the toe, a little later and softer
    delay = int(g.uniform(0.07, 0.11) * SR)
    toe = np.zeros(n)
    toe[delay:] = 0.5 * (0.6 * thud[: n - delay] * 0.6 + 0.9 * knock[: n - delay] + 0.3 * ring[: n - delay])
    out = (heel + toe) * weight
    return out


def make_steps():
    layouts = [
        # (seed, count, pace, level shape over the steps)
        (71, 6, 0.64, lambda u: 0.3 + 0.7 * u),                 # coming closer
        (72, 6, 0.6, lambda u: 1.0 - 0.75 * u),                 # going away
        (73, 7, 0.62, lambda u: 0.35 + 0.65 * np.sin(np.pi * u)),  # passing by
        (74, 5, 0.66, lambda u: 0.55 + 0.1 * u),                # then stopping, suddenly, nearby
    ]
    for index, (seed, count, pace, shape) in enumerate(layouts, start=1):
        g = rng(seed)
        total = int((count * pace + 1.4) * SR)
        mix = np.zeros(total)
        at = 0.25
        for k in range(count):
            u = k / max(count - 1, 1)
            weight = shape(u) * g.uniform(0.85, 1.1)
            one = step(seed * 10 + k, weight, brightness=shape(u))
            i0 = int(at * SR)
            mix[i0:i0 + len(one)] += one[: total - i0]
            # now and then the board answers with a creak
            if g.uniform() < 0.3:
                c = creak(seed * 100 + k, 0.35, 45, 70, [(200, 9, 1.0), (460, 10, 0.6)], stick=0.3) * 0.5 * weight
                j0 = i0 + int(0.05 * SR)
                mix[j0:j0 + len(c)] += c[: total - j0]
            at += pace * g.uniform(0.93, 1.08)
        # another room: rounded off, with the room's own tail
        mix = lowpass(mix, 1900)
        mix = reverb(mix, 1.5, 0.4, seed=80 + index)
        save(f"menu_steps_{index}.wav", normalise(trim_tail(fade(mix, 0.002, 0.2)), 0.8))


def main():
    make_music()
    make_buzz()
    make_zaps()
    make_pops()
    make_creaks()
    make_steps()
    return 0


if __name__ == "__main__":
    sys.exit(main())
