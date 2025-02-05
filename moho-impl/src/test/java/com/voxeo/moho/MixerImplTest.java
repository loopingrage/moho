/**
 * Copyright 2010-2011 Voxeo Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License.
 *
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.voxeo.moho;

import java.util.concurrent.ExecutionException;

import javax.media.mscontrol.Configuration;
import javax.media.mscontrol.MediaEventListener;
import javax.media.mscontrol.MsControlFactory;
import javax.media.mscontrol.Parameters;
import javax.media.mscontrol.join.Joinable.Direction;
import javax.media.mscontrol.mixer.MediaMixer;
import javax.media.mscontrol.networkconnection.NetworkConnection;
import javax.sdp.SdpFactory;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServlet;

import junit.framework.TestCase;

import org.hamcrest.Description;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.api.Action;
import org.jmock.api.Invocation;
import org.jmock.lib.legacy.ClassImposteriser;

import com.voxeo.moho.Participant.JoinType;
import com.voxeo.moho.event.MohoHangupEvent;
import com.voxeo.moho.media.fake.MockMediaSession;
import com.voxeo.moho.sip.SIPCallImpl;
import com.voxeo.moho.sip.fake.MockSipServlet;
import com.voxeo.moho.spi.ExecutionContext;

public class MixerImplTest extends TestCase {

  Mockery mockery = new Mockery() {
    {
      setImposteriser(ClassImposteriser.INSTANCE);
    }
  };

  // JSR309 mock
  MsControlFactory msFactory = mockery.mock(MsControlFactory.class);

  MockMediaSession mediaSession = mockery.mock(MockMediaSession.class);

  MediaMixer mixer = mockery.mock(MediaMixer.class);

  // JSR289 mock
  SipServlet servlet = new MockSipServlet(mockery);

  // Moho
  TestApp app = mockery.mock(TestApp.class);

  // ApplicationContextImpl is simple, no need to mock it.
  ExecutionContext appContext = new ApplicationContextImpl(app, msFactory, servlet);

  SipFactory sipFactory = appContext.getSipFactory();

  SdpFactory sdpFactory = appContext.getSdpFactory();

  MixerEndpoint address;

  MixerImpl mohoMixer;

  protected void setUp() throws Exception {
    super.setUp();
  }

  protected void tearDown() throws Exception {
    super.tearDown();
  }

  @SuppressWarnings("unchecked")
  public void testJoinAndUnjoin() {
    // prepare
    try {
      mockery.checking(new Expectations() {
        {
          // creation
          oneOf(msFactory).createMediaSession();
          will(returnValue(mediaSession));

          oneOf(mediaSession).createMediaMixer(with(any(Configuration.class)), with(any(Parameters.class)));
          will(returnValue(mixer));

          oneOf(mixer).addListener(with(any(MediaEventListener.class)));
          will(returnValue(null));
        }
      });
    }
    catch (Exception ex) {
      ex.printStackTrace();
    }

    // create mixer.
    address = (MixerEndpoint) appContext.createEndpoint("mscontrol://test");
    mohoMixer = (MixerImpl) address.create(null);

    // mock the call
    final SIPCallImpl call = mockery.mock(SIPCallImpl.class);
    final NetworkConnection callNet = mockery.mock(NetworkConnection.class);

    try {
      mockery.checking(new Expectations() {
        {
          allowing(call).getMediaObject();
          will(returnValue(callNet));

          // join
          oneOf(call).join(mohoMixer, JoinType.BRIDGE, Direction.DUPLEX);
          will(new Action() {
            @Override
            public void describeTo(Description description) {
            }

            @Override
            public Object invoke(Invocation invocation) throws Throwable {
              mohoMixer.addParticipant(call, JoinType.BRIDGE, Direction.DUPLEX, null);
              return null;
            }
          });
          // will(return)

          // unjoin
          oneOf(mixer).unjoin(callNet);

          oneOf(call).unjoin(mohoMixer, false);
        }
      });
    }
    catch (Exception ex) {
      ex.printStackTrace();
      fail(ex.getMessage());
    }

    // join
    try {
      mohoMixer.join(call, JoinType.BRIDGE, Direction.DUPLEX);
    }
    catch (IllegalStateException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    // unjoin
    try {
      mohoMixer.unjoin(call).get();
    }
    catch (InterruptedException e) {
      e.printStackTrace();
    }
    catch (ExecutionException e) {
      e.printStackTrace();
    }

    // verify the result.
    mockery.assertIsSatisfied();
  }

  /**
   * disconnect.
   */
  @SuppressWarnings("unchecked")
  public void testDisconnect() {
    // prepare
    try {
      mockery.checking(new Expectations() {
        {
          // creation
          oneOf(msFactory).createMediaSession();
          will(returnValue(mediaSession));

          oneOf(mediaSession).createMediaMixer(with(any(Configuration.class)), with(any(Parameters.class)));
          will(returnValue(mixer));

          oneOf(mixer).addListener(with(any(MediaEventListener.class)));
          will(returnValue(null));

          // release.
          oneOf(mediaSession).release();

          oneOf(mixer).release();
        }
      });
    }
    catch (Exception ex) {
      ex.printStackTrace();
      fail(ex.getMessage());
    }

    // create mixer.
    address = (MixerEndpoint) appContext.createEndpoint("mscontrol://test");
    mohoMixer = (MixerImpl) address.create(null);

    // disconnect.
    mohoMixer.disconnect();

    // verify the result.
    mockery.assertIsSatisfied();
  }

  interface TestApp extends Application {
    public void handleDisconnect(MohoHangupEvent event);
  }
}