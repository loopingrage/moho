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

package com.voxeo.moho.sample;

import javax.media.mscontrol.join.Joinable;

import com.voxeo.moho.Application;
import com.voxeo.moho.ApplicationContext;
import com.voxeo.moho.Call;
import com.voxeo.moho.State;
import com.voxeo.moho.Participant.JoinType;
import com.voxeo.moho.event.InviteEvent;
import com.voxeo.moho.sip.SIPEndpoint;

public class CallForward implements Application {

  ApplicationContext _ctx;

  SIPEndpoint _target;

  @Override
  public void destroy() {

  }

  @Override
  public void init(final ApplicationContext ctx) {
    _ctx = ctx;
    _target = (SIPEndpoint) _ctx.getEndpoint(ctx.getParameter("target"));
  }

  @State
  public void handleInvite(final InviteEvent e) {
    final Call call = e.acceptCall(this);
    try {
      call.join(e.getInvitee(), JoinType.DIRECT, Joinable.Direction.DUPLEX).get();
    }
    catch (final Exception ex) {
      call.join(_target, JoinType.DIRECT, Joinable.Direction.DUPLEX);
    }
  }
}
