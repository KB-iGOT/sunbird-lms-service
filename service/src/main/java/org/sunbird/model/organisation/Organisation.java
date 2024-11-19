package org.sunbird.model.organisation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * @desc POJO class for Organisation
 * @author Amit Kumar
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public class Organisation implements Serializable {

  private static final long serialVersionUID = 3617862727235741692L;
  private String id;
  private String channel;
  private String contactDetail;
  private String createdBy;
  private String createdDate;
  private String description;
  private String email;
  private String externalId;
  private String orgName;
  private Integer organisationType;
  private Integer organisationSubType;
  private String provider;
  private String rootOrgId;
  private String slug;
  private Integer status;
  private String updatedBy;
  private String updatedDate;
  private Boolean isSSOEnabled;
  private Boolean isTenant;
  private List<Map<String, String>> orgLocation;
  private String frameworkId;
  private String imgUrl;
  private String cqfId;
  private String registrationLink;
  private String qrRegistrationLink;
  private String registrationStartDate;
  private String registrationEndDate;
  private String logo;
  private String startDateRegistration;
  private String endDateRegistration;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getChannel() {
    return channel;
  }

  public void setChannel(String channel) {
    this.channel = channel;
  }

  public String getContactDetail() {
    return contactDetail;
  }

  public void setContactDetail(String contactDetail) {
    this.contactDetail = contactDetail;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  public String getCreatedDate() {
    return createdDate;
  }

  public void setCreatedDate(String createdDate) {
    this.createdDate = createdDate;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getExternalId() {
    return externalId;
  }

  public void setExternalId(String externalId) {
    this.externalId = externalId;
  }

  public String getOrgName() {
    return orgName;
  }

  public void setOrgName(String orgName) {
    this.orgName = orgName;
  }

  public Integer getOrganisationType() {
    return organisationType;
  }

  public void setOrganisationType(Integer organisationType) {
    this.organisationType = organisationType;
  }

  public Integer getOrganisationSubType() { return organisationSubType; }

  public void setOrganisationSubType(Integer organisationSubType) { this.organisationSubType = organisationSubType;  }

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getSlug() {
    return slug;
  }

  public void setSlug(String slug) {
    this.slug = slug;
  }

  public Integer getStatus() {
    return status;
  }

  public void setStatus(Integer status) {
    this.status = status;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(String updatedBy) {
    this.updatedBy = updatedBy;
  }

  public String getUpdatedDate() {
    return updatedDate;
  }

  public void setUpdatedDate(String updatedDate) {
    this.updatedDate = updatedDate;
  }

  @JsonProperty(value = "isSSOEnabled")
  public Boolean isSSOEnabled() {
    return isSSOEnabled;
  }

  public void setSSOEnabled(Boolean isSsoEnabled) {
    this.isSSOEnabled = isSsoEnabled;
  }

  @JsonProperty(value = "isTenant")
  public Boolean isTenant() {
    return isTenant;
  }

  public void setTenant(Boolean tenant) {
    isTenant = tenant;
  }

  public String getRootOrgId() {
    return rootOrgId;
  }

  public void setRootOrgId(String rootOrgId) {
    this.rootOrgId = rootOrgId;
  }

  public List<Map<String, String>> getOrgLocation() {
    return orgLocation;
  }

  public void setOrgLocation(List<Map<String, String>> orgLocation) {
    this.orgLocation = orgLocation;
  }

  public String getFrameworkId() { return frameworkId; }

  public void setFrameworkId(String frameworkId) { this.frameworkId = frameworkId; }

  public String getImgUrl() {
    return imgUrl;
  }

  public void setImgUrl(String imgUrl) {
    this.imgUrl = imgUrl;
  }

  public String getCqfId() { return cqfId; }

  public void setCqfId(String cqfId) { this.cqfId = cqfId; }

  public String getRegistrationLink() { return registrationLink; }

  public void setRegistrationLink(String registrationLink) {
    this.registrationLink = registrationLink;
  }

  public String getQrRegistrationLink() { return qrRegistrationLink; }

  public void setQrRegistrationLink(String qrRegistrationLink) {
    this.qrRegistrationLink = qrRegistrationLink;
  }

  public String getRegistrationStartDate() {
    return registrationStartDate;
  }

  public void setRegistrationStartDate(String registrationStartDate) {
    this.registrationStartDate = registrationStartDate;
  }

  public String getRegistrationEndDate() {
    return registrationEndDate;
  }

  public void setRegistrationEndDate(String registrationEndDate) {
    this.registrationEndDate = registrationEndDate;
  }

  public String getLogo() {
    return logo;
  }

  public void setLogo(String logo) {
    this.logo = logo;
  }

  public String getEndDateRegistration() {
    return endDateRegistration;
  }

  public void setEndDateRegistration(String endDateRegistration) {
    this.endDateRegistration = endDateRegistration;
  }

  public String getStartDateRegistration() {
    return startDateRegistration;
  }

  public void setStartDateRegistration(String startDateRegistration) {
    this.startDateRegistration = startDateRegistration;
  }
}
