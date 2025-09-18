package org.edu_sharing.elasticsearch.edu_sharing.client;


import java.util.List;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;


@JsonIgnoreProperties(ignoreUnknown = true)
public class MdsV2 {
		
	public static class Create {
		private boolean onlyMetadata;

		public boolean isOnlyMetadata() {
			return onlyMetadata;
		}

		public void setOnlyMetadata(boolean onlyMetadata) {
			this.onlyMetadata = onlyMetadata;
		}
		public Create() {

		}
	}
	private String name = null;
	private Create create = null;
	private List<WidgetV2> widgets = null;
	private List<ViewV2> views;
	private List<GroupV2> groups;
	private List<SortV2> sorts;


	@JsonProperty
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	@JsonProperty
	public Create getCreate() {
		return create;
	}
	public void setCreate(Create create) {
		this.create = create;
	}
	@JsonProperty
	public List<WidgetV2> getWidgets() {
		return widgets;
	}
	
	

	public void setWidgets(List<WidgetV2> widgets) {
		this.widgets = widgets;
	}
	@JsonProperty
	public List<ViewV2> getViews() {
		return views;
	}

	public void setViews(List<ViewV2> views) {
		this.views = views;
	}
	@JsonProperty
	public List<GroupV2> getGroups() {
		return groups;
	}

	public void setGroups(List<GroupV2> groups) {
		this.groups = groups;
	}

	@JsonProperty
	public List<SortV2> getSorts() {
		return sorts;
	}

	public void setSorts(List<SortV2> sorts) {
		this.sorts = sorts;
	}
}
