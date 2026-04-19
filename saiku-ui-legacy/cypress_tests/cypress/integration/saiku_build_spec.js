/*
 *   Copyright 2012 OSBI Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

describe('Saiku Analytics', function() {
  context('Tests login', function() {
    beforeEach(function() {
      cy.visit('http://localhost:8080/');
    });

    it('Login failed with blank inputs', function() {
      // click the Login button
      cy.get('a[href="#login"]')
        .contains('Login').should('have.class', 'btn').click();

      // has "Authentication failed for:" ?
      cy.contains('Authentication failed for:');
    });

    it('Login failed with wrong username and password', function() {
      // fill the username input with "admin1"
      cy.get('input[name="username"]')
        .type('admin1').should('have.value', 'admin1');

      // fill the password input with "123456"
      cy.get('input[name="password"]')
        .type('123456').should('have.value', '123456');

      // click the Login button
      cy.get('a[href="#login"]')
        .contains('Login').should('have.class', 'btn').click();

      // has "Authentication failed for: admin" ?
      cy.contains('Authentication failed for: admin');
    });

    it('Login passes with license in place', function() {
      // fill the username input with "admin"
      cy.get('input[name="username"]')
        .type('admin').should('have.value', 'admin');

      // fill the password input with "admin"
      cy.get('input[name="password"]')
        .type('admin').should('have.value', 'admin');

      // click the Login button
      cy.get('a[href="#login"]')
        .contains('Login').should('have.class', 'btn').click();

      // has new query icon in the toolbar?
      cy.get('a#new_query').should('have.class', 'new_tab');
    });
  });

  context('Tests creating queries', function() {
    it('Sample data cubes are available on initial install', function() {
      // click the new query icon
      cy.get('a#new_query').click();

      // check if "Foodmart" schema exists
      cy.get('select#cubesselect > optgroup').each(function($el) {
        if ($el.attr('label').indexOf('FoodMart') !== -1) {
          expect($el).to.have.attr('label', 'FoodMart  (foodmart)');
        }
        else {
          return;
        }

        return false;
      });
    });

    it('New queries can be created', function() {
      // select the cube "Sales"
      cy.get('select#cubesselect')
        .select('Sales').should('have.value', 'foodmart/FoodMart/FoodMart/Sales');

      // click the measure "Unit Sales"
      cy.get('.measure_tree .d_measure a[measure="Unit Sales"]')
        .contains('Unit Sales').should('have.class', 'measure').click();

      // click the dimension "Customer"
      cy.get('.dimension_tree .parent_dimension a[title="Customer"]')
        .contains('Customer').should('have.class', 'folder_collapsed').click();

      // click the level "Country"
      cy.get('.dimension_tree .parent_dimension a[level="Country"]')
        .contains('Country').should('have.class', 'level').click();
    });

    it('Charts render', function() {
      // click the render chart icon
      cy.get('.query_toolbar_vertical > ul li:nth-child(2) a#chart_icon')
        .should('have.class', 'render_chart').click();
    });
  });

  context('Tests saving and opening files', function() {
    it('Files can be saved', function() {
      // click the render table icon
      cy.get('.query_toolbar_vertical > ul li:nth-child(1) a#table_icon')
        .should('have.class', 'render_table').click();

      // click the save icon in workspace toolbar
      cy.get('.workspace_toolbar a#save_icon')
        .should('have.class', 'save').click();

      // click the folder "homes"
      cy.get('.RepositoryObjects li .folder_row')
        .contains('homes').click();

      // click the folder "home:admin"
      cy.get('.RepositoryObjects li .folder_content')
        .contains('home:admin').click();

      // create the "test_cypress.saiku" file
      cy.get('input#relative-file-path').type('test_cypress');

      // click the Save button
      cy.get('a[href="#save"]')
        .contains('Save').should('have.class', 'btn').click();
    });

    it('Files can be opened', function() {
      // click the open icon in workspace toolbar
      cy.get('.workspace_toolbar #new_icon')
        .should('have.class', 'open').click();

      // click the folder "home"
      cy.get('.RepositoryObjects li .folder_row')
        .contains('homes').click();

      // click the folder "home:admin"
      cy.get('.RepositoryObjects li .folder_content')
        .contains('home:admin').click();

      // Open the "test_cypress.saiku" file
      cy.get('.RepositoryObjects li .folder_content')
        .contains('test_cypress.saiku').dblclick();
    });
  });

  context('Tests files and folders can be secured with roles', function() {
    it('Folder can be secured with roles', function() {
      // click the open query icon
      cy.get('a#open_query').should('have.class', 'open_query').click();

      // click the folder "homes"
      cy.get('.RepositoryObjects li .folder_row')
        .contains('homes').click({ force: true });

      // click the edit permissions icon
      cy.get('.workspace_toolbar .for_folder a.edit_permissions')
        .should('have.class', 'button').click();

      // fill the filter_roles input with "ROLE_ADMIN"
      cy.get('input#filter_roles')
        .type('ROLE_ADMIN').should('have.value', 'ROLE_ADMIN');

      // check the boxes "READ", "WRITE" and "GRANT"
      cy.get('input[type="checkbox"].acl[value="READ"]').should('have.value', 'READ').click({ force: true });
      cy.get('input[type="checkbox"].acl[value="WRITE"]').should('have.value', 'WRITE').click({ force: true });
      cy.get('input[type="checkbox"].acl[value="GRANT"]').should('have.value', 'GRANT').click({ force: true });

      // click the Add button
      cy.get('input.add_role').should('have.value', 'Add').click();

      // click the Ok button
      cy.get('a[href="#ok"]')
        .contains('Ok').should('have.class', 'btn').click();
    });

    it('Files can be secured with roles', function() {
      // click the open query icon
      cy.get('a#open_query').should('have.class', 'open_query').click();

      // click the folder "homes"
      cy.get('.RepositoryObjects li .folder_row')
        .contains('homes').click({ force: true });

      // click the folder "home:admin"
      cy.get('.RepositoryObjects li .folder_content')
        .contains('home:admin').click({ force: true });

      // click the "test_cypress.saiku" file
      cy.get('.RepositoryObjects li .folder_content')
        .contains('test_cypress.saiku').click({ force: true });

      // click the edit permissions icon
      cy.get('.workspace_toolbar .for_folder a.edit_permissions')
        .should('have.class', 'button').click({ force: true });

      // fill the filter_roles input with "ROLE_ADMIN"
      cy.get('input#filter_roles')
        .type('ROLE_ADMIN').should('have.value', 'ROLE_ADMIN');

      // check the boxes "READ", "WRITE" and "GRANT"
      cy.get('input[type="checkbox"].acl[value="READ"]').should('have.value', 'READ').click({ force: true });
      cy.get('input[type="checkbox"].acl[value="WRITE"]').should('have.value', 'WRITE').click({ force: true });
      cy.get('input[type="checkbox"].acl[value="GRANT"]').should('have.value', 'GRANT').click({ force: true });

      // click the Add button
      cy.get('input.add_role').should('have.value', 'Add').click();

      // click the Ok button
      cy.get('a[href="#ok"]')
        .contains('Ok').should('have.class', 'btn').click();
    });
  });
});
