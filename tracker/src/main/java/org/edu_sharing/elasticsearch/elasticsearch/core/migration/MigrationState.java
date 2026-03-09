package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import lombok.Data;

import java.util.Date;

@Data
public class MigrationState {

    private Date updateDate2 = new Date();

    private int progressStep;
    private String progressContent;

    private String statusMessage;
}