/**
 * Copyright 2010 Voxeo Corporation Licensed under the Apache License, Version
 * 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law
 * or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.voxeo.moho;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import javax.media.mscontrol.MediaEventListener;
import javax.media.mscontrol.MediaObject;
import javax.media.mscontrol.MediaSession;
import javax.media.mscontrol.MsControlException;
import javax.media.mscontrol.MsControlFactory;
import javax.media.mscontrol.Parameters;
import javax.media.mscontrol.join.Joinable;
import javax.media.mscontrol.join.Joinable.Direction;
import javax.media.mscontrol.join.JoinableStream;
import javax.media.mscontrol.join.JoinableStream.StreamType;
import javax.media.mscontrol.mediagroup.MediaGroup;
import javax.media.mscontrol.mixer.MediaMixer;
import javax.media.mscontrol.mixer.MixerAdapter;
import javax.media.mscontrol.mixer.MixerEvent;
import javax.media.mscontrol.spi.Driver;
import javax.media.mscontrol.spi.DriverManager;

import org.apache.log4j.Logger;

import com.voxeo.moho.event.DispatchableEventSource;
import com.voxeo.moho.event.Event;
import com.voxeo.moho.event.EventSource;
import com.voxeo.moho.event.JoinCompleteEvent;
import com.voxeo.moho.event.JoinCompleteEvent.Cause;
import com.voxeo.moho.event.MohoActiveSpeakerEvent;
import com.voxeo.moho.event.MohoJoinCompleteEvent;
import com.voxeo.moho.event.MohoMediaResourceDisconnectEvent;
import com.voxeo.moho.event.MohoUnjoinCompleteEvent;
import com.voxeo.moho.event.Observer;
import com.voxeo.moho.event.UnjoinCompleteEvent;
import com.voxeo.moho.media.GenericMediaService;
import com.voxeo.moho.media.Input;
import com.voxeo.moho.media.Output;
import com.voxeo.moho.media.Prompt;
import com.voxeo.moho.media.Recording;
import com.voxeo.moho.media.input.InputCommand;
import com.voxeo.moho.media.output.OutputCommand;
import com.voxeo.moho.media.record.RecordCommand;
import com.voxeo.moho.spi.ExecutionContext;

public class MixerImpl extends DispatchableEventSource implements Mixer, ParticipantContainer, InternalParticipant {

  private static final Logger LOG = Logger.getLogger(MixerImpl.class);

  protected MixerEndpoint _address;

  protected MediaService<Mixer> _service;

  protected MediaSession _media;

  protected MediaMixer _mixer;

  protected boolean _clampDtmf;

  protected JoineeData _joinees = new JoineeData();

  protected Map<Object, Participant> activeInputParticipant = new HashMap<Object, Participant>();

  protected MixerImpl(final ExecutionContext context, final MixerEndpoint address, final Map<Object, Object> params,
      Parameters parameters) {
    super(context);
    try {
      MsControlFactory mf = null;
      if (params == null || params.size() == 0) {
        mf = context.getMSFactory();
      }
      else {
        final Driver driver = DriverManager.getDrivers().next();
        final Properties props = new Properties();
        for (final Map.Entry<Object, Object> entry : params.entrySet()) {
          final String key = String.valueOf(entry.getKey());
          final String value = entry.getValue() == null ? "" : entry.getValue().toString();
          props.setProperty(key, value);
        }
        if (props.getProperty(MsControlFactory.MEDIA_SERVER_URI) == null && address != null) {
          props.setProperty(MsControlFactory.MEDIA_SERVER_URI, address.getURI().toString());
        }
        mf = driver.getFactory(props);
      }
      _media = mf.createMediaSession();

      if (parameters != null && parameters.get(MediaMixer.ENABLED_EVENTS) != null) {
        _mixer = _media.createMediaMixer(MediaMixer.AUDIO_EVENTS, parameters);
      }
      else {
        _mixer = _media.createMediaMixer(MediaMixer.AUDIO, parameters);
      }

      _address = address;

      if ((address.getProperty("playTones") != null && !Boolean.valueOf(address.getProperty("playTones")))
          || (params != null && !Boolean.valueOf((String) params.get("playTones")))) {
        _clampDtmf = true;
      }

      _mixer.addListener(new MixerEventListener());
    }
    catch (final Exception e) {
      throw new MediaException(e);
    }
  }

  @Override
  public int hashCode() {
    return _mixer.hashCode();
  }

  @Override
  public boolean equals(final Object o) {
    if (!(o instanceof MixerImpl)) {
      return false;
    }
    if (this == o) {
      return true;
    }
    return _mixer.equals(((MixerImpl) o).getMediaObject());
  }

  @Override
  public String toString() {
    return new StringBuilder().append(MixerImpl.class.getSimpleName()).append("[").append(_mixer).append("]")
        .toString();
  }

  @Override
  public synchronized MediaService<Mixer> getMediaService() throws MediaException, IllegalStateException {
    checkState();
    if (_service == null) {
      try {
        _service = (MediaService<Mixer>) _context.getMediaServiceFactory().create((Mixer) this, _media, null);
        _service.getMediaGroup().join(Direction.DUPLEX, _mixer);
        return _service;
      }
      catch (final Exception e) {
        throw new MediaException(e);
      }
    }
    return _service;
  }

  @Override
  public synchronized void disconnect() {
    try {
      ((GenericMediaService) _service).release(true);
    }
    catch (final Exception e) {
      LOG.warn("Exception when release media service", e);
    }
    try {
      _mixer.release();
    }
    catch (final Exception e) {
      LOG.warn("Exception when release mixer", e);
    }
    try {
      _media.release();
    }
    catch (final Exception e) {
      LOG.warn("Exception when release mediaSession", e);
    }
    _media = null;

    Participant[] _joineesArray = _joinees.getJoinees();
    for (Participant participant : _joineesArray) {
      if (participant instanceof ParticipantContainer) {
        ((ParticipantContainer) participant).removeParticipant(this);

        MohoUnjoinCompleteEvent event = new MohoUnjoinCompleteEvent(participant, MixerImpl.this,
            UnjoinCompleteEvent.Cause.DISCONNECT, false);
        participant.dispatch(event);
        dispatch(new MohoUnjoinCompleteEvent(this, participant, UnjoinCompleteEvent.Cause.DISCONNECT, true));
      }
    }
    _joinees.clear();

    this.dispatch(new MohoMediaResourceDisconnectEvent<Mixer>(this));
  }

  @Override
  public Endpoint getAddress() {
    return _address;
  }

  @Override
  public Participant[] getParticipants() {
    return _joinees.getJoinees();
  }

  @Override
  public Participant[] getParticipants(final Direction direction) {
    return _joinees.getJoinees(direction);
  }

  @Override
  public void addParticipant(final Participant p, final JoinType type, final Direction direction, Participant realJoined) {
    _joinees.add(p, type, direction, realJoined);

    if (realJoined == null) {
      activeInputParticipant.put(p.getMediaObject(), p);
    }
    else {
      activeInputParticipant.put(realJoined.getMediaObject(), p);
    }
  }

  @Override
  public void removeParticipant(final Participant p) {
    JoinData joinData = _joinees.remove(p);

    if (joinData != null) {
      if (joinData.getRealJoined() == null) {
        activeInputParticipant.remove(p.getMediaObject());
      }
      else {
        activeInputParticipant.put(joinData.getRealJoined().getMediaObject(), p);
      }
    }
  }

  @Override
  public void removeJoinee(Participant other) {
    removeParticipant(other);
  }

  @Override
  public Joint join(final Participant other, final JoinType type, final Direction direction)
      throws IllegalStateException {
    synchronized (this) {
      checkState();
      if (_joinees.contains(other)) {
        return new JointImpl(_context.getExecutor(), new JointImpl.DummyJoinWorker(MixerImpl.this, other));
      }
    }

    if (other instanceof CallImpl) {
      Joint joint = null;
      if (isClampDtmf(null)) {
        try {
          joint = other.join(new ClampDtmfMixerAdapter(), type, direction);
        }
        catch (MsControlException ex) {
          LOG.warn("can't clamp DTMF", ex);
          joint = other.join(this, type, direction);
        }
      }
      else {
        joint = other.join(this, type, direction);
      }

      return joint;
    }
    else {
      if (!(other.getMediaObject() instanceof Joinable)) {
        throw new IllegalArgumentException("MediaObject is't joinable.");
      }
      return new JointImpl(_context.getExecutor(), new JoinWorker() {
        @Override
        public JoinCompleteEvent call() throws Exception {
          JoinCompleteEvent event = null;
          try {
            synchronized (MixerImpl.this) {
              if (MixerImpl.this.isClampDtmf(null)) {
                try {
                  ClampDtmfMixerAdapter clampMixerAdapter = new ClampDtmfMixerAdapter();
                  clampMixerAdapter._mixerAdapter.join(direction, (Joinable) other.getMediaObject());
                  _joinees.add(other, type, direction, clampMixerAdapter);
                  ((ParticipantContainer) other).addParticipant(MixerImpl.this, type, direction, clampMixerAdapter);
                }
                catch (MsControlException ex) {
                  LOG.warn("can't clamp DTMF", ex);
                  _mixer.join(direction, (Joinable) other.getMediaObject());
                  _joinees.add(other, type, direction);
                  ((ParticipantContainer) other).addParticipant(MixerImpl.this, type, direction, null);
                }
              }
              else {
                _mixer.join(direction, (Joinable) other.getMediaObject());
                _joinees.add(other, type, direction);
                ((ParticipantContainer) other).addParticipant(MixerImpl.this, type, direction, null);
              }

              event = new MohoJoinCompleteEvent(MixerImpl.this, other, Cause.JOINED, true);
            }
          }
          catch (final Exception e) {
            event = new MohoJoinCompleteEvent(MixerImpl.this, other, Cause.ERROR, e, true);
            throw new MediaException(e);
          }
          finally {
            MixerImpl.this.dispatch(event);
            MohoJoinCompleteEvent event2 = new MohoJoinCompleteEvent(other, MixerImpl.this, event.getCause(), false);
            other.dispatch(event2);
          }
          return event;
        }

        @Override
        public boolean cancel() {
          return false;
        }
      });
    }
  }

  protected synchronized UnjoinCompleteEvent doMixerUnjoin(final Participant p, boolean callOtherUnjoin)
      throws Exception {
    MohoUnjoinCompleteEvent event = null;
    if (!_joinees.contains(p)) {
      event = new MohoUnjoinCompleteEvent(MixerImpl.this, p, UnjoinCompleteEvent.Cause.NOT_JOINED, true);
      MixerImpl.this.dispatch(event);
      return event;
    }
    try {
      JoinData joinData = _joinees.remove(p);
      if (p.getMediaObject() instanceof Joinable) {

        if (joinData.getRealJoined() != null) {
        	if(callOtherUnjoin){
        		((ClampDtmfMixerAdapter) joinData.getRealJoined())._mixerAdapter.unjoin((Joinable) p.getMediaObject());
        	}
          ((ClampDtmfMixerAdapter) joinData.getRealJoined())._mixerAdapter.release();
        }
        else {
          if(callOtherUnjoin){
        	  _mixer.unjoin((Joinable) p.getMediaObject());
          }
        }

      }
      if (callOtherUnjoin) {
        ((InternalParticipant) p).unjoin(this, false);
      }

      event = new MohoUnjoinCompleteEvent(MixerImpl.this, p, UnjoinCompleteEvent.Cause.SUCCESS_UNJOIN, true);
    }
    catch (final Exception e) {
      LOG.error("", e);
      event = new MohoUnjoinCompleteEvent(MixerImpl.this, p, UnjoinCompleteEvent.Cause.FAIL_UNJOIN, e, true);
      throw e;
    }
    finally {
      if (event == null) {
        event = new MohoUnjoinCompleteEvent(MixerImpl.this, p, UnjoinCompleteEvent.Cause.FAIL_UNJOIN, true);
      }
      MixerImpl.this.dispatch(event);
    }

    return event;
  }

  @Override
  public Unjoint unjoin(final Participant p) {
    return mixerunjoin(p, true);
  }

  public Unjoint mixerunjoin(final Participant other, final boolean callOtherUnjoin) {
    Unjoint task = new UnjointImpl(_context.getExecutor(), new Callable<UnjoinCompleteEvent>() {
      @Override
      public UnjoinCompleteEvent call() throws Exception {
        return doMixerUnjoin(other, callOtherUnjoin);
      }
    });

    return task;
  }
  

  @Override
  public Unjoint unjoin(Participant other, boolean callPeerUnjoin) {
  	return mixerunjoin(other, callPeerUnjoin);
  }

  @Override
  public MediaObject getMediaObject() {
    return _mixer;
  }

  @Override
  public JoinableStream getJoinableStream(final StreamType arg0) throws MediaException, IllegalStateException {
    checkState();
    try {
      return _mixer.getJoinableStream(arg0);
    }
    catch (final MsControlException e) {
      throw new MediaException(e);
    }
  }

  @Override
  public JoinableStream[] getJoinableStreams() throws MediaException, IllegalStateException {
    checkState();
    try {
      return _mixer.getJoinableStreams();
    }
    catch (final MsControlException e) {
      throw new MediaException(e);
    }
  }

  protected void checkState() {
    if (_media == null) {
      throw new IllegalStateException();
    }
  }

  protected boolean isClampDtmf(Properties props) {
    boolean clampDTMF = _clampDtmf;
    if (props != null && props.get("playTones") != null) {
      if (Boolean.valueOf(props.getProperty("playTones"))) {
        clampDTMF = false;
      }
      else {
        clampDTMF = true;
      }
    }
    return clampDTMF;
  }

  @Override
  public Joint join(final Participant other, final JoinType type, final Direction direction, final Properties props) {
    synchronized (this) {
      checkState();
      if (_joinees.contains(other)) {
        return new JointImpl(_context.getExecutor(), new JointImpl.DummyJoinWorker(MixerImpl.this, other));
      }
    }

    if (other instanceof CallImpl) {
      Joint joint = null;
      if (isClampDtmf(props)) {
        try {
          joint = other.join(new ClampDtmfMixerAdapter(), type, direction);
        }
        catch (MsControlException ex) {
          LOG.warn("can't clamp DTMF", ex);
          joint = other.join(this, type, direction);
        }
      }
      else {
        joint = other.join(this, type, direction);
      }

      return joint;
    }
    else {
      if (!(other.getMediaObject() instanceof Joinable)) {
        throw new IllegalArgumentException("MediaObject is't joinable.");
      }
      return new JointImpl(_context.getExecutor(), new JoinWorker() {
        @Override
        public JoinCompleteEvent call() throws Exception {
          JoinCompleteEvent event = null;
          try {
            synchronized (MixerImpl.this) {
              if (MixerImpl.this.isClampDtmf(props)) {
                try {
                  ClampDtmfMixerAdapter clampMixerAdapter = new ClampDtmfMixerAdapter();
                  clampMixerAdapter._mixerAdapter.join(direction, (Joinable) other.getMediaObject());
                  _joinees.add(other, type, direction, clampMixerAdapter);
                  ((ParticipantContainer) other).addParticipant(MixerImpl.this, type, direction, clampMixerAdapter);
                }
                catch (MsControlException ex) {
                  LOG.warn("can't clamp DTMF", ex);
                  _mixer.join(direction, (Joinable) other.getMediaObject());
                  _joinees.add(other, type, direction);
                  ((ParticipantContainer) other).addParticipant(MixerImpl.this, type, direction, null);
                }
              }
              else {
                _mixer.join(direction, (Joinable) other.getMediaObject());
                _joinees.add(other, type, direction);
                ((ParticipantContainer) other).addParticipant(MixerImpl.this, type, direction, null);
              }

              event = new MohoJoinCompleteEvent(MixerImpl.this, other, Cause.JOINED, true);
            }
          }
          catch (final Exception e) {
            event = new MohoJoinCompleteEvent(MixerImpl.this, other, Cause.ERROR, e, true);
            throw new MediaException(e);
          }
          finally {
            MixerImpl.this.dispatch(event);
            MohoJoinCompleteEvent event2 = new MohoJoinCompleteEvent(other, MixerImpl.this, event.getCause(), false);
            other.dispatch(event2);
          }
          return event;
        }

        @Override
        public boolean cancel() {
          return false;
        }
      });
    }
  }

  public class ClampDtmfMixerAdapter implements Mixer, ParticipantContainer {
    protected MixerAdapter _mixerAdapter;

    public ClampDtmfMixerAdapter() throws MsControlException {
      super();
      _mixerAdapter = MixerImpl.this._mixer.createMixerAdapter(MixerAdapter.DTMF_CLAMP);
    }

    @Override
    public MediaService<Mixer> getMediaService() {
      return MixerImpl.this.getMediaService();
    }

    @Override
    public Joint join(Participant other, JoinType type, Direction direction, Properties props) {
      return MixerImpl.this.join(other, type, direction, props);
    }

    @Override
    public JoinableStream getJoinableStream(StreamType value) {
      JoinableStream result = null;
      try {
        result = _mixerAdapter.getJoinableStream(value);
      }

      catch (final MsControlException e) {
        throw new MediaException(e);
      }
      return result;
    }

    @Override
    public JoinableStream[] getJoinableStreams() {
      JoinableStream[] result = null;
      try {
        result = _mixerAdapter.getJoinableStreams();
      }
      catch (final MsControlException e) {
        throw new MediaException(e);
      }
      return result;
    }

    @Override
    public void disconnect() {
      MixerImpl.this.disconnect();
    }

    @Override
    public Endpoint getAddress() {
      return MixerImpl.this.getAddress();
    }

    @Override
    public MediaObject getMediaObject() {
      return _mixerAdapter;
    }

    @Override
    public Participant[] getParticipants() {
      return MixerImpl.this.getParticipants();
    }

    @Override
    public Participant[] getParticipants(Direction direction) {
      return MixerImpl.this.getParticipants(direction);
    }

    @Override
    public Joint join(Participant other, JoinType type, Direction direction) {
      return MixerImpl.this.join(other, type, direction);
    }

    @Override
    public Unjoint unjoin(Participant other) {
      return MixerImpl.this.unjoin(other);
    }

    // private void addListener(EventListener<?> listener) {
    // MixerImpl.this.addListener(listener);
    // }
    //
    // private <E extends Event<?>, T extends EventListener<E>> void
    // addListener(Class<E> type, T listener) {
    // MixerImpl.this.addListener(type, listener);
    // }
    //
    // private void addListeners(EventListener<?>... listeners) {
    // MixerImpl.this.addListeners(listeners);
    // }
    //
    // private <E extends Event<?>, T extends EventListener<E>> void
    // addListeners(Class<E> type, T... listener) {
    // MixerImpl.this.addListeners(type, listener);
    // }
    //
    // private void addObserver(Observer observer) {
    // MixerImpl.this.addObserver(observer);
    // }

    @Override
    public void addObserver(Observer... observers) {
      MixerImpl.this.addObserver(observers);
    }

    @Override
    public <S extends EventSource, T extends Event<S>> Future<T> dispatch(T event) {
      return MixerImpl.this.dispatch(event);
    }

    @Override
    public <S extends EventSource, T extends Event<S>> Future<T> dispatch(T event, Runnable afterExec) {
      return MixerImpl.this.dispatch(event, afterExec);
    }

    @Override
    public ApplicationContext getApplicationContext() {
      return MixerImpl.this.getApplicationContext();
    }

    @Override
    public String getApplicationState() {
      return MixerImpl.this.getApplicationState();
    }

    @Override
    public String getApplicationState(String FSM) {
      return MixerImpl.this.getApplicationState(FSM);
    }

    // private void removeListener(EventListener<?> listener) {
    // MixerImpl.this.removeListener(listener);
    // }

    @Override
    public void removeObserver(Observer listener) {
      MixerImpl.this.removeObserver(listener);
    }

    @Override
    public void setApplicationState(String state) {
      MixerImpl.this.setApplicationState(state);
    }

    @Override
    public void setApplicationState(String FSM, String state) {
      MixerImpl.this.setApplicationState(FSM, state);
    }

    @Override
    public String getId() {
      return MixerImpl.this.getId();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String name) {
      return (T) MixerImpl.this.getAttribute(name);
    }

    @Override
    public Map<String, Object> getAttributeMap() {
      return MixerImpl.this.getAttributeMap();
    }

    @Override
    public void setAttribute(String name, Object value) {
      MixerImpl.this.setAttribute(name, value);
    }

    @Override
    public void addParticipant(Participant p, JoinType type, Direction direction, Participant realJoined) {
      MixerImpl.this.addParticipant(p, type, direction, realJoined);
    }

    @Override
    public void removeParticipant(Participant p) {
      MixerImpl.this.removeParticipant(p);
    }

    public MixerImpl getMixer() {
      return MixerImpl.this;
    }

    @Override
    public Output<Mixer> output(String text) throws MediaException {
      return getMediaService().output(text);
    }

    @Override
    public Output<Mixer> output(URI media) throws MediaException {
      return getMediaService().output(media);
    }

    @Override
    public Output<Mixer> output(OutputCommand output) throws MediaException {
      return getMediaService().output(output);
    }

    @Override
    public Prompt<Mixer> prompt(String text, String grammar, int repeat) throws MediaException {
      return getMediaService().prompt(text, grammar, repeat);
    }

    @Override
    public Prompt<Mixer> prompt(URI media, String grammar, int repeat) throws MediaException {
      return getMediaService().prompt(media, grammar, repeat);
    }

    @Override
    public Prompt<Mixer> prompt(OutputCommand output, InputCommand input, int repeat) throws MediaException {
      return getMediaService().prompt(output, input, repeat);
    }

    @Override
    public Input<Mixer> input(String grammar) throws MediaException {
      return getMediaService().input(grammar);
    }

    @Override
    public Input<Mixer> input(InputCommand input) throws MediaException {
      return getMediaService().input(input);
    }

    @Override
    public Recording<Mixer> record(URI recording) throws MediaException {
      return getMediaService().record(recording);
    }

    @Override
    public Recording<Mixer> record(RecordCommand command) throws MediaException {
      return getMediaService().record(command);
    }

    @Override
    public MediaGroup getMediaGroup() {
      return getMediaService().getMediaGroup();
    }
  }

  // listener for Active speaker event.
  public class MixerEventListener implements MediaEventListener<MixerEvent> {
    @Override
    public void onEvent(MixerEvent event) {
      if (event.getEventType() == MixerEvent.ACTIVE_INPUTS_CHANGED) {
        Joinable[] joinables = event.getActiveInputs();
        List<Participant> activeSpeakers = new LinkedList<Participant>();
        if (joinables != null) {
          for (Joinable joinalbe : joinables) {
            Participant participant = activeInputParticipant.get(joinalbe);
            if (participant != null) {
              activeSpeakers.add(participant);
            }
          }

          MixerImpl.this.dispatch(new MohoActiveSpeakerEvent(MixerImpl.this, activeSpeakers
              .toArray(new Participant[] {})));
        }
      }
    }
  }

  @Override
  public Output<Mixer> output(String text) throws MediaException {
    return getMediaService().output(text);
  }

  @Override
  public Output<Mixer> output(URI media) throws MediaException {
    return getMediaService().output(media);
  }

  @Override
  public Output<Mixer> output(OutputCommand output) throws MediaException {
    return getMediaService().output(output);
  }

  @Override
  public Prompt<Mixer> prompt(String text, String grammar, int repeat) throws MediaException {
    return getMediaService().prompt(text, grammar, repeat);
  }

  @Override
  public Prompt<Mixer> prompt(URI media, String grammar, int repeat) throws MediaException {
    return getMediaService().prompt(media, grammar, repeat);
  }

  @Override
  public Prompt<Mixer> prompt(OutputCommand output, InputCommand input, int repeat) throws MediaException {
    return getMediaService().prompt(output, input, repeat);
  }

  @Override
  public Input<Mixer> input(String grammar) throws MediaException {
    return getMediaService().input(grammar);
  }

  @Override
  public Input<Mixer> input(InputCommand input) throws MediaException {
    return getMediaService().input(input);
  }

  @Override
  public Recording<Mixer> record(URI recording) throws MediaException {
    return getMediaService().record(recording);
  }

  @Override
  public Recording<Mixer> record(RecordCommand command) throws MediaException {
    return getMediaService().record(command);
  }

  @Override
  public MediaGroup getMediaGroup() {
    return getMediaService().getMediaGroup();
  }


}
