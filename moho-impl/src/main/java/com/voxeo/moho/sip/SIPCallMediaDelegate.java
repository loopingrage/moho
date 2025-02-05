/**
 * Copyright 2010 Voxeo Corporation
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

package com.voxeo.moho.sip;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Map;

import javax.media.mscontrol.MsControlException;
import javax.media.mscontrol.join.Joinable.Direction;
import javax.media.mscontrol.networkconnection.NetworkConnection;
import javax.media.mscontrol.networkconnection.SdpPortManagerEvent;
import javax.sdp.SdpException;
import javax.sdp.SessionDescription;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.apache.log4j.Logger;

import com.voxeo.moho.NegotiateException;
import com.voxeo.moho.Participant.JoinType;
import com.voxeo.moho.SignalException;
import com.voxeo.moho.sip.SIPCall.State;
import com.voxeo.moho.sip.SIPCallImpl.HoldState;

public class SIPCallMediaDelegate extends SIPCallDelegate {

  private static final Logger LOG = Logger.getLogger(SIPCallMediaDelegate.class);

  protected SipServletRequest _req;

  protected SipServletResponse _res;

  protected boolean _isWaiting;

  protected SIPCallMediaDelegate() {
    super();
  }

  @Override
  protected void handleAck(final SIPCallImpl call, final SipServletRequest req) throws Exception {
    try {
      call.processSDPAnswer(req);
      _isWaiting = false;
      call.notifyAll();
    }
    catch (final Exception e) {
      LOG.error("Exception", e);
      call.fail(e);
    }
  }

  @Override
  protected void handleReinvite(final SIPCallImpl call, final SipServletRequest req, final Map<String, String> headers)
      throws Exception {
    _req = req;
    _isWaiting = true;
    call.processSDPOffer(req);

    while (call.isAnswered() & _isWaiting) {
      try {
        call.wait();
      }
      catch (final InterruptedException e) {
        // ignore
      }
    }
    if (call.getSIPCallState() != State.ANSWERED) {
      throw new SignalException("Call state error: " + call);
    }

    // if it is a hold request, hold peer.
    SIPCallImpl peerCall = (SIPCallImpl) call.getLastPeer();
    if (peerCall != null) {

      String sdp = new String(call.getRemoteSdp(), "iso8859-1");
      if (sdp.indexOf("sendonly") > 0) {
        if (call.getJoinType(peerCall) == JoinType.BRIDGE) {
          ((NetworkConnection) peerCall.getMediaObject()).unjoin((NetworkConnection) call.getMediaObject());
        }
        peerCall.hold(true);
      }
      else if (sdp.indexOf("sendrecv") > 0) {
        if (call.getJoinType(peerCall) == JoinType.BRIDGE) {
          ((NetworkConnection) peerCall.getMediaObject()).join(Direction.DUPLEX,
              (NetworkConnection) call.getMediaObject());
        }
        peerCall.unhold();
      }
    }
  }

  @Override
  protected void handleReinviteResponse(SIPCallImpl call, SipServletResponse res, Map<String, String> headers)
      throws Exception {
    _res = res;

    if (res.getRequest().getAttribute(SIPCallDelegate.SIPCALL_HOLD_REQUEST) != null
        || res.getRequest().getAttribute(SIPCallDelegate.SIPCALL_UNHOLD_REQUEST) != null) {
      try {
        _res.createAck().send();
        call.holdResp();
      }
      catch (IOException e) {
        LOG.error("IOException when sending ACK", e);
        call.setHoldState(HoldState.None);
        call.notify();
        call.fail(e);
      }
    }
    else {
      call.processSDPOffer(res);
    }
  }

  @Override
  protected void handleSdpEvent(final SIPCallImpl call, final SdpPortManagerEvent event) {
    if (event.getEventType().equals(SdpPortManagerEvent.OFFER_GENERATED)
        || event.getEventType().equals(SdpPortManagerEvent.ANSWER_GENERATED)) {
      if (call.isMutingProcess()) {
        try {
          _res.createAck().send();
          if (call.getMuteState() == HoldState.Muting) {
            call.setMuteState(HoldState.Muted);
          }
          else if (call.getMuteState() == HoldState.UnMuting) {
            call.setMuteState(HoldState.None);
          }
        }
        catch (IOException e) {
          LOG.error("IOException when sending ACK", e);
          call.setHoldState(HoldState.None);
          call.fail(e);
        }
        finally {
          call.notify();
        }
      }
      else if (call.isHoldingProcess()) {
        call.holdResp();
      }
      else {
        if (event.isSuccessful()) {
          byte[] sdp = event.getMediaServerSdp();

          SessionDescription sessionDescription = null;
          try {
            String remoteSdp = new String(call.getRemoteSdp(), "iso8859-1");
            // TODO improve the parse.
            if (remoteSdp.indexOf("sendonly") > 0) {
              sessionDescription = createRecvonlySDP(call, sdp);
              sdp = sessionDescription.toString().getBytes("iso8859-1");
            }
          }
          catch (UnsupportedEncodingException e1) {
            LOG.error("", e1);
          }
          catch (SdpException e) {
            LOG.error("", e);
          }

          call.setLocalSDP(sdp);
          try {
            final SipServletResponse res = _req.createResponse(SipServletResponse.SC_OK);
            res.setContent(sdp, "application/sdp");
            res.send();
          }
          catch (final Exception e) {
            LOG.error("", e);
            call.fail(e);
          }
        }
        else {
          SIPHelper.handleErrorSdpPortManagerEvent(event, _req);
          call.fail(new NegotiateException(event));
        }
      }

    }
  }

  @Override
  protected void hold(SIPCallImpl call, boolean send) throws MsControlException, IOException, SdpException {
    ((NetworkConnection) call.getMediaObject()).getSdpPortManager().processSdpOffer(
        send ? createRecvonlySDP(call, call.getRemoteSdp()).toString().getBytes() : createSendonlySDP(call,
            call.getRemoteSdp()).toString().getBytes());
    call.getMediaService(true);

    SipServletRequest reInvite = call.getSipSession().createRequest("INVITE");
    reInvite.setAttribute(SIPCallDelegate.SIPCALL_HOLD_REQUEST, "true");
    reInvite.setContent(createSendonlySDP(call, call.getLocalSDP()), "application/sdp");
    reInvite.send();
  }

  @Override
  protected void mute(SIPCallImpl call) throws MsControlException, IOException, SdpException {
    call.getMediaService(true);

    SipServletRequest reInvite = call.getSipSession().createRequest("INVITE");
    reInvite.setAttribute(SIPCallDelegate.SIPCALL_MUTE_REQUEST, "true");
    reInvite.setContent(createSendonlySDP(call, call.getLocalSDP()), "application/sdp");
    reInvite.send();
  }

  @Override
  protected void unhold(SIPCallImpl call) throws MsControlException, IOException, SdpException {
    ((NetworkConnection) call.getMediaObject()).getSdpPortManager().processSdpOffer(
        createSendrecvSDP(call, call.getRemoteSdp()).toString().getBytes());

    call.getMediaService(true);

    SipServletRequest reInvite = call.getSipSession().createRequest("INVITE");
    reInvite.setAttribute(SIPCallDelegate.SIPCALL_UNHOLD_REQUEST, "true");
    reInvite.setContent(createSendrecvSDP(call, call.getLocalSDP()), "application/sdp");
    reInvite.send();
  }

  @Override
  protected void unmute(SIPCallImpl call) throws MsControlException, IOException, SdpException {
    call.getMediaService(true);

    SipServletRequest reInvite = call.getSipSession().createRequest("INVITE");
    reInvite.setAttribute(SIPCallDelegate.SIPCALL_UNMUTE_REQUEST, "true");
    reInvite.setContent(createSendrecvSDP(call, call.getLocalSDP()), "application/sdp");
    reInvite.send();
  }
}
