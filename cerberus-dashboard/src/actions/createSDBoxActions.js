/*
 * Copyright (c) 2020 Nike, inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import React from 'react';
import axios from 'axios';
import * as constants from '../constants/actions';
import * as cms from '../constants/cms';
import environmentService from '../service/EnvironmentService';
import * as messengerActions from './messengerActions';
import * as modalActions from './modalActions';
import * as appActions from './appActions';
import ApiError from '../components/ApiError/ApiError';
import * as humps from 'humps';
import { push } from 'connected-react-router';

import { getLogger } from "../utils/logger";
var log = getLogger('create-new-sdb-actions');

export function submitCreateNewSDB(data, token) {

    let formData = humps.decamelizeKeys(data);

    log.debug("submitting data to create sdb cms endpoint\n" + JSON.stringify(formData, null, 2));

    return function (dispatch) {
        dispatch(submittingNewSDBRequest());
        axios({
            method: 'post',
            url: `${environmentService.getDomain()}${cms.BUCKET_RESOURCE}`,
            headers: { 'X-Cerberus-Token': token },
            data: formData,
            timeout: 10 * 1000 // 10 seconds
        })
            .then(function (response) {
                dispatch(modalActions.popModal());
                dispatch(clearSecureData());
                dispatch(resetVersionBrowserState());
                dispatch(appActions.fetchSideBarData(token));
                dispatch(push(`/manage-safe-deposit-box/${response.data.id}`));
            })
            .catch(function ({ response }) {
                log.error('Failed to create new SDB', response);
                dispatch(messengerActions.addNewMessage(<ApiError message="Failed to create new SDB" response={response} />));
                dispatch(resetSubmittingNewSDBRequest());
            });
    };
}

export function initCreateNewSDB(categoryId) {
    return {
        type: constants.CREATE_NEW_SDB_INIT,
        payload: { categoryId: categoryId }
    };
}

export function submittingNewSDBRequest() {
    return {
        type: constants.SUBMITTING_NEW_SDB_REQUEST
    };
}

export function resetSubmittingNewSDBRequest() {
    return {
        type: constants.RESET_SUBMITTING_NEW_SDB_REQUEST
    };
}

export function clearSecureData() {
    return {
        type: constants.RESET_SDB_DATA
    };
}

export function resetVersionBrowserState() {
    return {
        type: constants.RESET_VERSION_BROWSER_STATE
    };
}
