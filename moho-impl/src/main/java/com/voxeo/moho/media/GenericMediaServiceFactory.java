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

package com.voxeo.moho.media;

import java.util.Map;

import javax.media.mscontrol.MediaSession;
import javax.media.mscontrol.MsControlException;
import javax.media.mscontrol.Parameters;
import javax.media.mscontrol.mediagroup.MediaGroup;

import org.apache.log4j.Logger;

import com.voxeo.moho.MediaException;
import com.voxeo.moho.MediaService;
import com.voxeo.moho.MediaServiceFactory;
import com.voxeo.moho.event.EventSource;
import com.voxeo.moho.media.dialect.GenericDialect;
import com.voxeo.moho.media.dialect.MediaDialect;
import com.voxeo.moho.spi.ExecutionContext;

public class GenericMediaServiceFactory implements MediaServiceFactory {

  private static final Logger LOG = Logger.getLogger(GenericMediaServiceFactory.class);

  private MediaDialect _dialect;

  public GenericMediaServiceFactory() {
    _dialect = new GenericDialect();
  }

  public GenericMediaServiceFactory(MediaDialect dialect) {
    _dialect = dialect;
  }

  @Override
  public <T extends EventSource> MediaService<T> create(final T parent, final MediaSession session, Parameters params) {
    MediaGroup group = null;
    try {
      group = session.createMediaGroup(MediaGroup.PLAYER_RECORDER_SIGNALDETECTOR_SIGNALGENERATOR, params);
    }
    catch (final MsControlException e1) {
      try {
        group = session.createMediaGroup(MediaGroup.PLAYER_RECORDER_SIGNALDETECTOR, params);
      }
      catch (final MsControlException e2) {
        try {
          group = session.createMediaGroup(MediaGroup.PLAYER, params);
        }
        catch (final MsControlException e3) {
          throw new MediaException(e3);
        }
      }
    }
    return new GenericMediaService<T>(parent, group, _dialect);
  }

  @Override
  public void init(ExecutionContext context, Map<String, String> properties) {
    Class<? extends MediaDialect> mediaDialectClass = com.voxeo.moho.media.dialect.GenericDialect.class;
    final String mediaDialectClassName = properties.get("mediaDialectClass");
    try {
      if (mediaDialectClassName != null) {
        mediaDialectClass = (Class<? extends MediaDialect>) Class.forName(mediaDialectClassName);
      }
      _dialect = mediaDialectClass.newInstance();
      LOG.info("Moho is creating media service with dialect (" + _dialect + ").");
    }
    catch (Exception ex) {
      LOG.error("Moho is unable to create media dialect (" + mediaDialectClassName + ")", ex);
    }
  }

  @Override
  public void destroy() {

  }

  @Override
  public String getName() {
    return MediaServiceFactory.class.getName();
  }
}
