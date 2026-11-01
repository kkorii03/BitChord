package com.music.bitchord.playback.smart

import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the DSP analyzer on signals whose answer is known: a C major and an
 * A minor I-IV-V-I progression, and a 120 BPM kick.
 *
 * The key profiles are compared by correlation rather than raw overlap; with
 * raw overlap the two best candidates differed by under 1% on exactly these
 * progressions, so keyConfidence read 0 even though the key was right. Keeping
 * the synthetic input here makes that regression visible without a real track.
 */
class SyntheticKeyAndBeatTest {

    private data class Measured(
        val key: String,
        val keyConfidence: Double,
        val bpm: Double,
        val beatConfidence: Double,
    )

    @Test
    fun `a C major progression is identified with usable confidence`() {
        val result = analyze().getValue("cmajor")
        assertEquals("C major", result.key)
        assertTrue(result.keyConfidence >= 0.5, "C major keyConfidence was ${result.keyConfidence}")
    }

    @Test
    fun `an A minor progression is identified with usable confidence`() {
        val result = analyze().getValue("aminor")
        assertEquals("A minor", result.key)
        assertTrue(result.keyConfidence >= 0.5, "A minor keyConfidence was ${result.keyConfidence}")
    }

    @Test
    fun `a 120 BPM kick is detected as 120 BPM with a trusted grid`() {
        val result = analyze().getValue("kick120")
        assertTrue(abs(result.bpm - 120.0) < 2.0, "detected ${result.bpm} BPM")
        assertTrue(result.beatConfidence >= 0.5, "beatConfidence was ${result.beatConfidence}")
    }

    private fun analyze(): Map<String, Measured> {
        val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "native/analyzer/audio_analysis.cpp").isFile }
            ?: error("Could not find the native analyzer from ${System.getProperty("user.dir")}")
        val compiler = listOfNotNull(
            System.getenv("CXX")?.takeIf { it.isNotBlank() },
            "g++",
            "c++",
            "clang++",
        ).firstOrNull { candidate ->
            runCatching {
                ProcessBuilder(candidate, "--version")
                    .redirectErrorStream(true)
                    .start()
                    .also { it.inputStream.readBytes() }
                    .waitFor() == 0
            }.getOrDefault(false)
        } ?: error("No C++ compiler is available to build the analyzer test harness")

        val directory = Files.createTempDirectory("bitchord-analyzer-test").toFile()
        return try {
            val source = File(directory, "synthetic.cpp")
            val binary = File(directory, "synthetic")
            source.writeText(HARNESS)
            val command = listOf(
                compiler,
                "-std=c++17",
                "-O2",
                "-I", File(root, "native").path,
                source.path,
                File(root, "native/analyzer/audio_analysis.cpp").path,
                File(root, "native/analyzer/tempo_analysis.cpp").path,
                "-o", binary.path,
            )
            val build = ProcessBuilder(command).redirectErrorStream(true).start()
            val buildOutput = build.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, build.waitFor(), "analyzer harness failed to build:\n$buildOutput")

            val run = ProcessBuilder(binary.path).redirectErrorStream(true).start()
            val output = run.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, run.waitFor(), "analyzer harness failed:\n$output")
            output.lineSequence()
                .filter { it.startsWith("RESULT\t") }
                .map { line ->
                    val parts = line.split('\t')
                    parts[1] to Measured(
                        key = parts[2],
                        keyConfidence = parts[3].toDouble(),
                        bpm = parts[4].toDouble(),
                        beatConfidence = parts[5].toDouble(),
                    )
                }
                .toMap()
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        /** 30 s of four 7.5 s chords, plus a 30 s 120 BPM kick. */
        const val HARNESS = """
#include "analyzer/audio_analysis.h"

#include <cmath>
#include <cstdio>
#include <vector>

namespace {

constexpr double kPi = 3.14159265358979323846;

double MidiFrequency(int midi) {
  return 440.0 * std::pow(2.0, (midi - 69) / 12.0);
}

std::vector<float> Progression(const int chords[4][3], double sample_rate) {
  constexpr double kChordSeconds = 7.5;
  const size_t length = static_cast<size_t>(sample_rate * kChordSeconds * 4);
  std::vector<float> samples(length, 0.0f);
  for (size_t index = 0; index < length; ++index) {
    const double time = index / sample_rate;
    const size_t chord = static_cast<size_t>(time / kChordSeconds) % 4;
    const double local = time - static_cast<size_t>(time / kChordSeconds) * kChordSeconds;
    const double envelope = std::min(1.0, local / 0.05) * std::min(1.0, (kChordSeconds - local) / 0.05);
    for (int note = 0; note < 3; ++note) {
      const int midi = chords[chord][note];
      const double frequency = MidiFrequency(midi);
      samples[index] += static_cast<float>(0.06 * envelope * std::sin(2.0 * kPi * frequency * time));
      samples[index] += static_cast<float>(0.04 * envelope * std::sin(2.0 * kPi * frequency / 2.0 * time));
    }
  }
  return samples;
}

std::vector<float> Kick(double sample_rate) {
  constexpr double kSeconds = 30.0;
  constexpr double kBpm = 120.0;
  const size_t length = static_cast<size_t>(sample_rate * kSeconds);
  std::vector<float> samples(length, 0.0f);
  const double beat = 60.0 / kBpm;
  for (size_t index = 0; index < length; ++index) {
    const double time = index / sample_rate;
    const double local = std::fmod(time, beat);
    const double envelope = std::exp(-local * 18.0);
    const double frequency = 55.0 + 90.0 * std::exp(-local * 25.0);
    samples[index] = static_cast<float>(0.7 * envelope * std::sin(2.0 * kPi * frequency * local));
  }
  return samples;
}

void Report(const char* label, const std::vector<float>& samples, double sample_rate) {
  const auto result = bitchord::smart::AnalyzeAudio(samples, sample_rate, samples.size() / sample_rate);
  std::printf(
    "RESULT\t%s\t%s\t%.6f\t%.3f\t%.6f\n",
    label,
    result.key.c_str(),
    result.key_confidence,
    result.bpm,
    result.beat_confidence
  );
}

}  // namespace

int main() {
  constexpr double kRate = 11025.0;
  const int c_major[4][3] = {{60, 64, 67}, {65, 69, 72}, {67, 71, 74}, {60, 64, 67}};
  const int a_minor[4][3] = {{57, 60, 64}, {62, 65, 69}, {64, 68, 71}, {57, 60, 64}};
  Report("cmajor", Progression(c_major, kRate), kRate);
  Report("aminor", Progression(a_minor, kRate), kRate);
  Report("kick120", Kick(kRate), kRate);
  return 0;
}
"""
    }
}
