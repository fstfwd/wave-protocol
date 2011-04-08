/**
 * Copyright 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.waveprotocol.box.server.frontend;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.waveprotocol.box.common.CommonConstants.INDEX_WAVE_ID;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import junit.framework.TestCase;

import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.waveprotocol.box.common.DeltaSequence;
import org.waveprotocol.box.common.ExceptionalIterator;
import org.waveprotocol.box.common.IndexWave;
import org.waveprotocol.box.common.comms.WaveClientRpc.WaveletVersion;
import org.waveprotocol.box.server.common.CoreWaveletOperationSerializer;
import org.waveprotocol.box.server.frontend.ClientFrontend.OpenListener;
import org.waveprotocol.box.server.util.WaveletDataUtil;
import org.waveprotocol.box.server.waveserver.WaveBus;
import org.waveprotocol.box.server.waveserver.WaveServerException;
import org.waveprotocol.box.server.waveserver.WaveletProvider;
import org.waveprotocol.box.server.waveserver.WaveletProvider.SubmitRequestListener;
import org.waveprotocol.wave.federation.Proto.ProtocolWaveletDelta;
import org.waveprotocol.wave.model.document.operation.DocOp;
import org.waveprotocol.wave.model.document.operation.impl.DocOpBuilder;
import org.waveprotocol.wave.model.id.IdConstants;
import org.waveprotocol.wave.model.id.IdFilter;
import org.waveprotocol.wave.model.id.IdFilters;
import org.waveprotocol.wave.model.id.IdURIEncoderDecoder;
import org.waveprotocol.wave.model.id.WaveId;
import org.waveprotocol.wave.model.id.WaveletId;
import org.waveprotocol.wave.model.id.WaveletName;
import org.waveprotocol.wave.model.operation.OperationException;
import org.waveprotocol.wave.model.operation.wave.BlipContentOperation;
import org.waveprotocol.wave.model.operation.wave.TransformedWaveletDelta;
import org.waveprotocol.wave.model.operation.wave.WaveletBlipOperation;
import org.waveprotocol.wave.model.operation.wave.WaveletOperation;
import org.waveprotocol.wave.model.operation.wave.WaveletOperationContext;
import org.waveprotocol.wave.model.testing.DeltaTestUtil;
import org.waveprotocol.wave.model.version.HashedVersion;
import org.waveprotocol.wave.model.version.HashedVersionFactory;
import org.waveprotocol.wave.model.version.HashedVersionFactoryImpl;
import org.waveprotocol.wave.model.wave.ParticipantId;
import org.waveprotocol.wave.model.wave.data.BlipData;
import org.waveprotocol.wave.model.wave.data.ReadableWaveletData;
import org.waveprotocol.wave.model.wave.data.WaveletData;
import org.waveprotocol.wave.util.escapers.jvm.JavaUrlCodec;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Tests for {@link ClientFrontendImpl}.
 */
public class ClientFrontendImplTest extends TestCase {
  private static final IdURIEncoderDecoder URI_CODEC =
      new IdURIEncoderDecoder(new JavaUrlCodec());
  private static final HashedVersionFactory HASH_FACTORY =
      new HashedVersionFactoryImpl(URI_CODEC);

  private static final WaveId WAVE_ID = WaveId.of("example.com", "waveId");
  private static final WaveletId W1 =
      WaveletId.of("example.com", IdConstants.CONVERSATION_ROOT_WAVELET);
  private static final WaveletId W2 = WaveletId.of("example.com", "conv+2");
  private static final WaveletName WN1 = WaveletName.of(WAVE_ID, W1);
  private static final WaveletName WN2 = WaveletName.of(WAVE_ID, W2);
  private static final WaveletName INDEX_WAVELET_NAME =
      WaveletName.of(INDEX_WAVE_ID, WaveletId.of("example.com", WAVE_ID.getId()));

  private static final ParticipantId USER = new ParticipantId("user@example.com");
  private static final DeltaTestUtil UTIL = new DeltaTestUtil(USER);

  private static final HashedVersion V0 = HASH_FACTORY.createVersionZero(WN1);
  private static final HashedVersion V1 = HashedVersion.unsigned(1L);
  private static final HashedVersion V2 = HashedVersion.unsigned(2L);

  private static final TransformedWaveletDelta DELTA = TransformedWaveletDelta.cloneOperations(
      USER, V1, 0, ImmutableList.of(UTIL.addParticipant(USER)));
  private static final DeltaSequence DELTAS = DeltaSequence.of(DELTA);
  private static final ProtocolWaveletDelta SERIALIZED_DELTA =
      CoreWaveletOperationSerializer.serialize(DELTA);
  private static final Collection<WaveletVersion> NO_KNOWN_WAVELETS =
      Collections.<WaveletVersion>emptySet();

  private ClientFrontendImpl clientFrontend;
  private WaveletProvider waveletProvider;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    waveletProvider = mock(WaveletProvider.class);
    when(waveletProvider.getWaveletIds(any(WaveId.class))).thenReturn(ImmutableSet.<WaveletId>of());

    WaveBus waveBus = mock(WaveBus.class);
    clientFrontend = new ClientFrontendImpl(HASH_FACTORY, waveletProvider, "example.com");
  }

  public void testCannotOpenWavesWhenNotLoggedIn() throws Exception {
    OpenListener listener = mock(OpenListener.class);
    clientFrontend.openRequest(null, WAVE_ID, IdFilters.ALL_IDS, NO_KNOWN_WAVELETS, listener);
    verify(listener).onFailure("Not logged in");

    CommittedWaveletSnapshot snapshot = provideWavelet(WN1);
    clientFrontend.waveletUpdate(snapshot.snapshot, DELTAS);
    Mockito.verifyNoMoreInteractions(listener);
  }

  public void testOpenEmptyWaveReceivesChannelIdAndMarker() {
    OpenListener listener = openWave(IdFilters.ALL_IDS);
    verifyChannelId(listener);
    verifyMarker(listener, WAVE_ID);
  }

  public void testTwoSubscriptionsReceiveDifferentChannelIds() {
    OpenListener listener1 = openWave(IdFilters.ALL_IDS);
    String ch1 = verifyChannelId(listener1);

    OpenListener listener2 = openWave(IdFilters.ALL_IDS);
    String ch2 = verifyChannelId(listener2);

    assertFalse(ch1.equals(ch2));
  }

  public void testOpenWaveRecievesSnapshotsThenMarker() throws Exception {
    CommittedWaveletSnapshot snapshot1 = provideWavelet(WN1);
    CommittedWaveletSnapshot snapshot2 = provideWavelet(WN2);
    when(waveletProvider.getWaveletIds(WAVE_ID)).thenReturn(ImmutableSet.of(W1, W2));

    OpenListener listener = openWave(IdFilters.ALL_IDS);
    verify(listener).onUpdate(eq(WN1), eq(snapshot1), eq(DeltaSequence.empty()),
        eq(V0), isNullMarker(), any(String.class));
    verify(listener).onUpdate(eq(WN2), eq(snapshot2), eq(DeltaSequence.empty()),
        eq(V0), isNullMarker(), any(String.class));
    verifyMarker(listener, WAVE_ID);
  }

  /**
   * Tests that a snapshot not matching the subscription filter is not received.
   * @throws WaveServerException
   */
  @SuppressWarnings("unchecked") // Mock container
  public void testUnsubscribedSnapshotNotRecieved() throws Exception {
    OpenListener listener = openWave(IdFilter.ofPrefixes("non-existing"));
    verifyChannelId(listener);
    verifyMarker(listener, WAVE_ID);

    ReadableWaveletData wavelet = provideWavelet(WN1).snapshot;
    clientFrontend.waveletUpdate(wavelet, DELTAS);
    verify(listener, Mockito.never()).onUpdate(eq(WN1),
        any(CommittedWaveletSnapshot.class), Matchers.anyList(),
        any(HashedVersion.class), isNullMarker(), anyString());
  }

  /**
   * Tests that we get deltas.
   */
  public void testReceivedDeltasSentToClient() throws Exception {
    CommittedWaveletSnapshot snapshot = provideWavelet(WN1);
    when(waveletProvider.getWaveletIds(WAVE_ID)).thenReturn(ImmutableSet.of(W1));

    OpenListener listener = openWave(IdFilters.ALL_IDS);
    verify(listener).onUpdate(eq(WN1), eq(snapshot), eq(DeltaSequence.empty()),
        eq(V0), isNullMarker(), any(String.class));
    verifyMarker(listener, WAVE_ID);

    TransformedWaveletDelta delta = TransformedWaveletDelta.cloneOperations(USER, V2, 1234567890L,
        Arrays.asList(UTIL.noOp()));
    DeltaSequence deltas = DeltaSequence.of(delta);
    clientFrontend.waveletUpdate(snapshot.snapshot, deltas);
    verify(listener).onUpdate(eq(WN1), isNullSnapshot(), eq(deltas),
        isNullVersion(), isNullMarker(), anyString());
  }

  /**
   * Tests that submit requests are forwarded to the wavelet provider.
   */
  public void testSubmitForwardedToWaveletProvider() {
    OpenListener openListener = openWave(IdFilters.ALL_IDS);
    String channelId = verifyChannelId(openListener);

    SubmitRequestListener submitListener = mock(SubmitRequestListener.class);
    clientFrontend.submitRequest(USER, WN1, SERIALIZED_DELTA, channelId, submitListener);
    verify(waveletProvider).submitRequest(eq(WN1), eq(SERIALIZED_DELTA),
        any(SubmitRequestListener.class));
    verifyZeroInteractions(submitListener);
  }

  public void testCannotSubmitToIndexWave() throws WaveServerException {
    provideWaves(Collections.<WaveId> emptySet());
    OpenListener openListener = openWave(INDEX_WAVE_ID, IdFilters.ALL_IDS);
    String channelId = verifyChannelId(openListener);

    SubmitRequestListener submitListener = mock(SubmitRequestListener.class);
    clientFrontend.submitRequest(USER, INDEX_WAVELET_NAME, SERIALIZED_DELTA, channelId,
        submitListener);
    verify(submitListener).onFailure(anyString());
  }

  public void testCannotSubmitAsDifferentUser() {
    ParticipantId otherParticipant = new ParticipantId("another@example.com");
    OpenListener openListener = openWave(IdFilters.ALL_IDS);
    String channelId = verifyChannelId(openListener);

    SubmitRequestListener submitListener = mock(SubmitRequestListener.class);
    clientFrontend.submitRequest(otherParticipant, WN1, SERIALIZED_DELTA, channelId,
        submitListener);
    verify(submitListener).onFailure(anyString());
    verify(submitListener, never()).onSuccess(anyInt(), (HashedVersion) any(), anyLong());
  }

  // FIXME (Yuri Z.) Make this test work. The issue is - the participants set
  // for the wavelet is empty, so the size of wavelets is 0 and
  // isDeltasStartingAt(0) is failing. It wasn't a problem before fix to issue
  // http://code.google.com/p/wave-protocol/issues/detail?id=231 was applied as
  // authoorization in order to access a wavelet was not
  // enforced.
  /**
  public void testIndexContainsWaveFromStore() throws Exception {
    provideWaves(Collections.singleton(WAVE_ID));
    CommittedWaveletSnapshot snapshot1 = provideWavelet(WN1);
    clientFrontend.initialiseAllWaves();

    OpenListener listener = openWave(INDEX_WAVE_ID, IdFilters.ALL_IDS);
    verifyMarker(listener, INDEX_WAVE_ID);

    WaveletName indexWavelet1 = IndexWave.indexWaveletNameFor(WAVE_ID);
    verify(listener).onUpdate(eq(indexWavelet1), isNullSnapshot(),
        isDeltasStartingAt(0), isNullVersion(), isNullMarker(), any(String.class));
  }
  */

  /**
   * Tests that if we open the index wave, we don't get updates from the
   * original wave if they contain no interesting operations (add/remove
   * participant or text).
   */
  public void testUninterestingDeltasDontUpdateIndex() throws WaveServerException {
    provideWaves(Collections.<WaveId> emptySet());
    clientFrontend.initialiseAllWaves();

    OpenListener listener = openWave(INDEX_WAVE_ID, IdFilters.ALL_IDS);
    verifyChannelId(listener);
    verifyMarker(listener, INDEX_WAVE_ID);

    HashedVersion v1 = HashedVersion.unsigned(1L);
    TransformedWaveletDelta delta = makeDelta(USER, v1, 0L, UTIL.noOp());
    DeltaSequence deltas = DeltaSequence.of(delta);

    WaveletData wavelet = WaveletDataUtil.createEmptyWavelet(WN1, USER, V0, 1234567890L);
    clientFrontend.waveletUpdate(wavelet, deltas);

    WaveletName dummyWaveletName = ClientFrontendImpl.createDummyWaveletName(INDEX_WAVE_ID);
    verify(listener, Mockito.never()).onUpdate(eq(dummyWaveletName),
        any(CommittedWaveletSnapshot.class),
        isDeltasStartingAt(0),
        any(HashedVersion.class), isNullMarker(), anyString());
  }

  /**
   * Tests that a delta involving an addParticipant and a characters op
   * gets pushed through to the index wave as deltas that just summarise
   * the changes to the digest text and the participants, ignoring any
   * text from the first \n onwards.
   */
  public void testInterestingDeltasUpdateIndex() throws OperationException, WaveServerException {
    provideWaves(Collections.singleton(WAVE_ID));
    CommittedWaveletSnapshot snapshot1 = provideWavelet(WN1);
    clientFrontend.initialiseAllWaves();

    OpenListener listener = openWave(INDEX_WAVE_ID, IdFilters.ALL_IDS);
    verifyMarker(listener, INDEX_WAVE_ID);

    WaveletData wavelet = ((WaveletData) snapshot1.snapshot);
    BlipData blip = WaveletDataUtil.addEmptyBlip(wavelet, "default", USER, 0L);
    blip.getContent().consume(makeAppend(0, "Hello, world\nignored text"));

    waveletUpdate(V2, 0L, wavelet, UTIL.addParticipant(USER));

    WaveletOperation expectedDigestOp =
        makeAppendOp(IndexWave.DIGEST_DOCUMENT_ID, 0, "Hello, world", new WaveletOperationContext(
            IndexWave.DIGEST_AUTHOR, 0L, 1, V2));
    HashedVersion v1 = HashedVersion.unsigned(1);
    DeltaSequence expectedDeltas = DeltaSequence.of(
        makeDelta(USER, v1, 0L, UTIL.addParticipant(USER)),
        makeDelta(IndexWave.DIGEST_AUTHOR, V2, 0L, expectedDigestOp));
    verify(listener).onUpdate(eq(INDEX_WAVELET_NAME), isNullSnapshot(), eq(expectedDeltas),
        isNullVersion(), isNullMarker(), any(String.class));
  }

  /**
   * Opens a wave and returns a mock listener.
   */
  private ClientFrontend.OpenListener openWave(WaveId waveId, IdFilter filter) {
    OpenListener openListener = mock(OpenListener.class);
    clientFrontend.openRequest(USER, waveId, filter, NO_KNOWN_WAVELETS, openListener);
    return openListener;
  }

  private ClientFrontend.OpenListener openWave(IdFilter filter) {
    return openWave(WAVE_ID, filter);
  }

  private TransformedWaveletDelta makeDelta(ParticipantId author, HashedVersion endVersion,
      long timestamp, WaveletOperation... operations) {
    return TransformedWaveletDelta.cloneOperations(author, endVersion, timestamp,
        Arrays.asList(operations));
  }

  /**
   * Initialises the wavelet provider to provide a collection of waves.
   */
  private void provideWaves(Collection<WaveId> waves) throws WaveServerException {
    when(waveletProvider.getWaveIds()).thenReturn(
        ExceptionalIterator.FromIterator.<WaveId, WaveServerException> create(
            waves.iterator()));
  }

  /**
   * Prepares the wavelet provider to provide a new wavelet.
   *
   * @param name new wavelet name
   * @return the new wavelet snapshot
   */
  private CommittedWaveletSnapshot provideWavelet(WaveletName name) throws WaveServerException,
      OperationException {
    WaveletData wavelet = WaveletDataUtil.createEmptyWavelet(name, USER, V0, 1234567890L);
    DELTA.get(0).apply(wavelet);
    CommittedWaveletSnapshot snapshot = new CommittedWaveletSnapshot(wavelet, V0);
    when(waveletProvider.getSnapshot(name)).thenReturn(snapshot);
    when(waveletProvider.getHistory(name, V0, V1)).thenReturn(DELTAS);
    when(waveletProvider.getWaveletIds(name.waveId)).thenReturn(ImmutableSet.of(name.waveletId));
    return snapshot;
  }

  private void waveletUpdate(HashedVersion endVersion, long timestamp,
      WaveletData wavelet, WaveletOperation... operations) {
    TransformedWaveletDelta delta = makeDelta(USER, endVersion, timestamp, operations);
    DeltaSequence deltas = DeltaSequence.of(delta);
    clientFrontend.waveletUpdate(wavelet, deltas);
  }

  private static DocOp makeAppend(int retain, String text) {
    DocOpBuilder builder = new DocOpBuilder();
    if (retain > 0) {
      builder.retain(retain);
    }
    builder.characters(text);
    return builder.build();
  }

  private static WaveletBlipOperation makeAppendOp(String documentId, int retain, String text,
      WaveletOperationContext context) {
    return new WaveletBlipOperation(documentId,
        new BlipContentOperation(context, makeAppend(retain, text)));
  }

  /**
   * Verifies that the listener received a channel id.
   *
   * @return the channel id received
   */
  private static String verifyChannelId(OpenListener listener) {
    ArgumentCaptor<String> channelIdCaptor = ArgumentCaptor.forClass(String.class);
    verify(listener).onUpdate(any(WaveletName.class), isNullSnapshot(), eq(DeltaSequence.empty()),
        isNullVersion(), isNullMarker(), channelIdCaptor.capture());
    return channelIdCaptor.getValue();
  }

  /**
   * Verifies that the listener received a marker.
   */
  private static void verifyMarker(OpenListener listener, WaveId waveId) {
    ArgumentCaptor<WaveletName> waveletNameCaptor = ArgumentCaptor.forClass(WaveletName.class);
    verify(listener).onUpdate(waveletNameCaptor.capture(), isNullSnapshot(),
        eq(DeltaSequence.empty()), isNullVersion(), eq(true), (String) Mockito.isNull());
    assertEquals(waveId, waveletNameCaptor.getValue().waveId);
  }

  private static CommittedWaveletSnapshot isNullSnapshot() {
    return (CommittedWaveletSnapshot) Mockito.isNull();
  }

  private static HashedVersion isNullVersion() {
    return (HashedVersion) Mockito.isNull();
  }

  private static Boolean isNullMarker() {
    return (Boolean) Mockito.isNull();
  }

  private static List<TransformedWaveletDelta> isDeltasStartingAt(final long version) {
    return argThat(new ArgumentMatcher<List<TransformedWaveletDelta>>() {
      @Override
      public boolean matches(Object sequence) {
        if (sequence != null) {
          DeltaSequence s = (DeltaSequence) sequence;
          return (s.size() > 0) && (s.getStartVersion() == version);
        }
        return false;
      }
    });
  }
}
