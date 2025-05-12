FROM ${docker.from.openjdk.17.liberica.debian}

########################################################################################################################

RUN set -eux \
    && apt-get update -y \
    && apt-get install -y --no-install-recommends \
        curl \
        wget \
		nano \
        wait-for-it \
    && apt-get clean \
    && rm -r /var/lib/apt/lists/*

########################################################################################################################

ENV ALF_HOME /opt/alfresco
ENV PATH $ALF_HOME/bin:$PATH

WORKDIR $ALF_HOME

COPY assets/entrypoint.sh bin/

RUN set -eux \
	&& find bin -type f -name '*.sh' -exec chmod +x {} \; \
    && adduser --uid=1000 --home=$ALF_HOME --disabled-password --gecos "" --shell=/bin/bash worker \
    && chown -RL worker:worker . \
    && chown -RL worker:worker /tmp

USER worker

########################################################################################################################

RUN set -eux \
    && mkdir synonyms \
    && chown worker:worker synonyms

########################################################################################################################

COPY --chown=worker:worker artifacts/edu_sharing-community-repository-plugin-elastic-tracker-${org.edu_sharing:edu_sharing-community-repository-plugin-elastic-tracker:jar.version}.jar ./

########################################################################################################################

RUN set -eux \
    && mkdir profiler \
    && wget -qO- https://github.com/Granulate/async-profiler/releases/download/v2.0g1/async-profiler-2.0-linux-x64.tar.gz | tar xvz -C profiler

COPY --chown=worker:worker assets/profiler profiler/

########################################################################################################################

EXPOSE 8080 8081

ENTRYPOINT ["entrypoint.sh"]

########################################################################################################################

LABEL git.branch=${git.branch} \
git.closest.tag.name=${git.closest.tag.fixed} \
git.commit.id=${git.commit.id} \
git.dirty=${git.dirty} \
mvn.project.artifactId=${project.artifactId} \
mvn.project.groupId=${project.groupId} \
mvn.project.version=${project.version}
