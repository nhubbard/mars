package edu.missouristate.mars.mips.instructions.syscalls;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaEventListener;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ToneGeneratorTest {
  @Test
  void synchronousToneWaitsForTheTrackAndClosesTheSequencer() throws Exception {
    var device = new RecordingSequencer();
    var failures = new ArrayList<Exception>();
    var executor = Executors.newSingleThreadExecutor();
    try (var generator = new ToneGenerator(device::proxy, failures::add)) {
      var tone = executor.submit(() -> generator.generateToneSynchronously((byte) 65, 120, (byte) 8, (byte) 90));
      assertThat(device.started.await(3, TimeUnit.SECONDS)).isTrue();
      assertThat(tone).isNotDone();
      assertThat(device.tempo).isEqualTo(1000f);
      var track = device.sequence.getTracks()[0];
      assertEvent(track.get(0), 0, ShortMessage.PROGRAM_CHANGE, 8, 0);
      assertEvent(track.get(1), 0, ShortMessage.NOTE_ON, 65, 90);
      assertEvent(track.get(2), 120, ShortMessage.NOTE_OFF, 65, 90);
      device.listener.meta(new MetaMessage(47, new byte[0], 0));
      tone.get(3, TimeUnit.SECONDS);
      assertThat(device.closed.getCount()).isZero();
      assertThat(failures).isEmpty();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void asynchronousToneReturnsWhilePlayingAndSessionCloseInterruptsIt() throws Exception {
    var device = new RecordingSequencer();
    var failures = new ArrayList<Exception>();
    try (var generator = new ToneGenerator(device::proxy, failures::add)) {
      generator.generateTone((byte) 60, 60_000, (byte) 0, (byte) 80);
      assertThat(device.started.await(3, TimeUnit.SECONDS)).isTrue();
      assertThat(device.closed.getCount()).isEqualTo(1);
    }
    assertThat(device.closed.await(3, TimeUnit.SECONDS)).isTrue();
    assertThat(failures).isEmpty();
  }

  @Test
  void reportsMissingDevicesAndClosesPlayersThatFailDuringInitialization() {
    var unavailable = new MidiUnavailableException("Device unavailable");
    var failures = new ArrayList<Exception>();
    try (var generator = new ToneGenerator(() -> { throw unavailable; }, failures::add)) {
      generator.generateToneSynchronously((byte) 60, 200, (byte) 0, (byte) 80);
    }
    var badOpen = new RecordingSequencer();
    badOpen.openFailure = new MidiUnavailableException("Device busy");
    try (var generator = new ToneGenerator(badOpen::proxy, failures::add)) {
      generator.generateToneSynchronously((byte) 60, 200, (byte) 0, (byte) 80);
    }
    var badSequence = new RecordingSequencer();
    badSequence.sequenceFailure = new InvalidMidiDataException("Cannot configure sequence");
    try (var generator = new ToneGenerator(badSequence::proxy, failures::add)) {
      generator.generateToneSynchronously((byte) 60, 200, (byte) 0, (byte) 80);
    }
    assertThat(badOpen.closed.getCount()).isZero();
    assertThat(badSequence.closed.getCount()).isZero();
    assertThat(failures).containsExactly(unavailable, badOpen.openFailure, badSequence.sequenceFailure);
  }

  private static void assertEvent(MidiEvent event, long tick, int command, int first, int second) {
    var message = (ShortMessage) event.getMessage();
    assertThat(event.getTick()).isEqualTo(tick);
    assertThat(message.getCommand()).isEqualTo(command);
    assertThat(message.getChannel()).isZero();
    assertThat(message.getData1()).isEqualTo(first);
    assertThat(message.getData2()).isEqualTo(second);
  }

  private static final class RecordingSequencer {
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch closed = new CountDownLatch(1);
    Sequence sequence;
    float tempo;
    MetaEventListener listener;
    MidiUnavailableException openFailure;
    InvalidMidiDataException sequenceFailure;

    Sequencer proxy() {
      return (Sequencer) Proxy.newProxyInstance(
          Sequencer.class.getClassLoader(), new Class<?>[] {Sequencer.class}, (proxy, method, args) -> {
            switch (method.getName()) {
              case "open" -> {
                if (openFailure != null) throw openFailure;
              }
              case "setTempoInMPQ" -> tempo = (Float) args[0];
              case "setSequence" -> {
                if (sequenceFailure != null) throw sequenceFailure;
                sequence = (Sequence) args[0];
              }
              case "addMetaEventListener" -> {
                listener = (MetaEventListener) args[0];
                return true;
              }
              case "start" -> started.countDown();
              case "close" -> closed.countDown();
              default -> throw new UnsupportedOperationException(method.getName());
            }
            return null;
          });
    }
  }
}
