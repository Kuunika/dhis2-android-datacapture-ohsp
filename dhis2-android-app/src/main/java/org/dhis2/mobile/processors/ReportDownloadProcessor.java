/*
 * Copyright (c) 2014, Araz Abishov
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package org.dhis2.mobile.processors;

import android.content.Context;
import android.content.Intent;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;

import com.google.gson.JsonObject;

import org.dhis2.mobile.io.holders.DataElementOperand;
import org.dhis2.mobile.io.holders.DatasetInfoHolder;
import org.dhis2.mobile.io.json.JsonHandler;
import org.dhis2.mobile.io.json.ParsingException;
import org.dhis2.mobile.io.models.CategoryCombo;
import org.dhis2.mobile.io.models.CategoryOption;
import org.dhis2.mobile.io.models.Field;
import org.dhis2.mobile.io.models.Form;
import org.dhis2.mobile.io.models.Group;
import org.dhis2.mobile.network.HTTPClient;
import org.dhis2.mobile.network.Response;
import org.dhis2.mobile.network.URLConstants;
import org.dhis2.mobile.ui.activities.DataEntryActivity;
import org.dhis2.mobile.utils.PrefUtils;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class ReportDownloadProcessor {

    private ReportDownloadProcessor() {
    }

    public static void download(Context context, DatasetInfoHolder info) {
        String url = buildUrl(context, info);
        String creds = PrefUtils.getCredentials(context);
        Response response = HTTPClient.get(url, creds);

        Form form = null;
        if (response.getCode() >= 200 && response.getCode() < 300) {
            form = parseForm(response.getBody());
            if(form != null){
                List<DataElementOperand> compulsoryDataElementOperandList =
                        downloadCompulsoryDataElementUIds(context, info);
                if (compulsoryDataElementOperandList == null) {
                    return;
                }
                addCompulsoryDataElements(compulsoryDataElementOperandList, form);
                }
                CategoryOptionRelationsByDataSetDownloadProcessor
                        categoryOptionRelationsByDataSetDownloadProcessor =
                        new CategoryOptionRelationsByDataSetDownloadProcessor();
                categoryOptionRelationsByDataSetDownloadProcessor.download(context, info);
                removeFieldsWithInvalidCategoryOptionRelation(form, info);
        }

        Intent intent = new Intent(DataEntryActivity.TAG);
        intent.putExtra(Response.CODE, response.getCode());

        if (form != null) {
            intent.putExtra(Response.BODY, (Parcelable) form);
        }

        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }
    private static List<DataElementOperand> downloadCompulsoryDataElementUIds(Context context,
            DatasetInfoHolder info) {
        CompulsoryDataElementUIdsDownloadProcessor
                compulsoryDataElementUIdsDownloadProcessor = new CompulsoryDataElementUIdsDownloadProcessor();

        return compulsoryDataElementUIdsDownloadProcessor.download(context, info);
    }

    private static void addCompulsoryDataElements(List<DataElementOperand> compulsoryUIds, Form form) {
        if (form == null && compulsoryUIds == null) {
            return;
        }
        for (DataElementOperand dataElementOperand : compulsoryUIds) {
            for (Group group : form.getGroups()) {
                addCompulsoryDataElements(dataElementOperand, group);
            }
        }
    }

    private static void addCompulsoryDataElements(DataElementOperand dataElementOperand, Group group) {
        for (Field field : group.getFields()) {
            if (field.getDataElement().equals(dataElementOperand.getDataElementUid())) {
                if(field.getCategoryOptionCombo().equals(dataElementOperand.getCategoryOptionComboUid())) {
                    field.setCompulsory(true);
                }
            }
        }
    }

    private static Form removeFieldsWithInvalidCategoryOptionRelation(Form form,
            DatasetInfoHolder info) {
        for (Group group : form.getGroups()) {
            ArrayList<Field> validFields = getValidatedFieldList(
                    group, info);
            group.setFields(validFields);
        }
        return form;
    }

    @NonNull
    private static ArrayList<Field> getValidatedFieldList(
            Group group, DatasetInfoHolder info) {
        ArrayList<Field> validFields = new ArrayList<>();
        for (int i = 0; i < group.getFields().size(); i++) {
            Field field = group.getFields().get(i);
            checkIfAFieldIsValid(validFields, field, group.getLabel(), info);
        }
        return validFields;
    }

    private static void checkIfAFieldIsValid(
            ArrayList<Field> validFields, Field field, String section, DatasetInfoHolder info) {
        HashMap<String, List<CategoryCombo>> categoryComboByDataElement =
                info.getCategoryComboByDataElement();
        HashMap<String, List<String>> categoryOptionComboBySection = info.getCategoryComboDataElementBySection();

        if (categoryComboByDataElement.containsKey(field.getDataElement())) {
            if(isValidField(field, section, info, categoryComboByDataElement,
                    categoryOptionComboBySection)){
                validFields.add(field);
            }
        }
    }

    private static boolean isValidField(Field field, String section,
            DatasetInfoHolder info, HashMap<String, List<CategoryCombo>> categoryComboByDataElement,
            HashMap<String, List<String>> categoryOptionComboBySection) {
        if (categoryComboByDataElement.get(field.getDataElement()) == null) {
            if (isValidDefaultField(field, info)) return true;
            if (isValidSectionField(field, section, categoryOptionComboBySection)) return true;
        } else if (isAValidCategoryOptionCombo(categoryComboByDataElement, field)) {
            return true;
        }
        return false;
    }

    private static boolean isValidDefaultField(Field field, DatasetInfoHolder info) {
        if (info.getDefaultCategoryCombo().getCategoryOptionComboUIdList().contains(
                field.getCategoryOptionCombo())) {
            return true;
        }
        return false;
    }

    private static boolean isValidSectionField(Field field, String section,
            HashMap<String, List<String>> categoryOptionComboBySection) {
        if (categoryOptionComboBySection!=null && categoryOptionComboBySection.containsKey(section)){
            for(String validCategoryOptionUId:categoryOptionComboBySection.get(section)) {
                if (field.getCategoryOptionCombo().equals(validCategoryOptionUId)){
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isAValidCategoryOptionCombo(
            HashMap<String, List<CategoryCombo>> dataElementCategoryOptionRelation, Field field) {
        for (CategoryCombo categoryCombo : dataElementCategoryOptionRelation.get(
                field.getDataElement())) {
            for (String categoryOptionComboUId : categoryCombo.getCategoryOptionComboUIdList()) {
                if (field.getCategoryOptionCombo().equals(categoryOptionComboUId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String buildUrl(Context context, DatasetInfoHolder info) {
        String server = PrefUtils.getServerURL(context);
        String categoryOptions = buildCategoryOptionsString(info);
        String url = server
                + URLConstants.DATASET_VALUES_URL + "/" + info.getFormId() + "/"
                + URLConstants.FORM_PARAM + info.getOrgUnitId()
                + URLConstants.PERIOD_PARAM + info.getPeriod();
        if (categoryOptions != null) {
            url = url + URLConstants.CATEGORY_OPTIONS_PARAM + categoryOptions;
        }

        return url;
    }

    private static String buildCategoryOptionsString(DatasetInfoHolder info) {
        List<String> categoryOptions = new ArrayList<>();

        // extracting uids
        if (info.getCategoryOptions() != null && !info.getCategoryOptions().isEmpty()) {
            for (CategoryOption categoryOption : info.getCategoryOptions()) {
                categoryOptions.add(categoryOption.getId());
            }
        }

        if (!categoryOptions.isEmpty()) {
            return "[" + TextUtils.join(",", categoryOptions) + "]";
        }

        return null;
    }

    private static Form parseForm(String responseBody) {
        if (responseBody != null) {
            try {
                JsonObject jsonForm = JsonHandler.buildJsonObject(responseBody);
                return JsonHandler.fromJson(jsonForm, Form.class);
            } catch (NullPointerException e) {
                e.printStackTrace();
            } catch (ParsingException e) {
                e.printStackTrace();
            }
        }

        return null;
    }
}
