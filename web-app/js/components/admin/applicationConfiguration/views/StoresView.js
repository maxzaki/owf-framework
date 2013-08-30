define([
    './Description',
    './StoreView',
    '../collections/Stores',
    '../util/util',
    'jquery',
    'underscore',
    'backbone'
], function(ApplicationConfigurationDescriptionView,
    StoreView,
    Stores,
    Util,
    $,
    _,
    Backbone) {

    return Backbone.View.extend({

        id: 'applicationConfigurationStores',
        className: 'app_config_list_view_panel',

        storeViews: null,
        model: null,
        descriptionView: null,
        button: null,

        btnTpl: '<div id="app-config-add-store-container">' + '<a href="#" target="_blank" id="app-config-add-store">Add Store</a>' + '</div>',

        events: {
            'click #app-config-add-store': 'addBtnClicked',
            'click .app-config-store-view .edit': 'editBtnClicked',
            'click .app-config-store-view .delete': 'deleteBtnClicked'
        },

        initialize: function() {
            this.storeViews = {};

            _.bindAll(this, "renderItem");

            this.buildModel();
            this.buildCollection();

            this.collection.fetch({
                data: $.param({
                    widgetTypes: 'store'
                })
            }).complete(_.bind(function() {
                this.render();
            }, this));
        },

        buildModel: function() {
            this.model = new Backbone.Model({
                title: 'Stores',
                description: 'The icon and name for connected Stores is displayed here.'
            });
        },

        buildCollection: function() {
            this.collection = new Stores([], {
                parse: true
            });
        },

        render: function() {
            this.descriptionView = new ApplicationConfigurationDescriptionView({
                model: this.model
            });
            this.descriptionView.render();

            this.$el.append(this.descriptionView.el);

            this.collection.each(this.renderItem);

            this.button = _.template(this.btnTpl, {});
            this.$el.append(this.button);
        },

        renderItem: function(model) {
            var storeView = new StoreView({
                model: model,
                id: 'app-config-store-view-' + model.get('widgetGuid')
            });

            storeView.render();
            this.$el.append(storeView.el);
            this.storeViews[model.get('widgetGuid')] = storeView;
        },

        addBtnClicked: function(e) {
            e.preventDefault();
            $(e.currentTarget).fadeOut('fast').fadeIn('fast');
        },

        editBtnClicked: function(e) {
            this.addBtnClicked(e);
            var storeId = $(e.currentTarget).parent().data('store-id');
        },

        deleteBtnClicked: function(e) {
            var storeId = $(e.currentTarget).parent().data('store-id');

            this._deleteStoreWidget(storeId);
        },

        _deleteStoreWidget: function(storeId) {
            var me = this,
                storeIds = [];
            storeIds.push(storeId);

            $.ajax({
                url: Util.contextPath + '/prefs/widget',
                type: 'POST',
                dataType: 'json',
                data: {
                    _method: 'PUT',
                    widgetsToUpdate: "[]",
                    widgetGuidsToDelete: "[\"" + storeId + "\"]",
                    updateOrder: false
                }
            })
                .done(_.bind(function(data, textStatus) {
                    me._deleteStoreWidgetDefinition(storeId);
                }, me))
                .fail(_.bind(function(data, textStatus, error) {
                    console.log('first delete failed');
                }, me));
        },

        _deleteStoreWidgetDefinition: function(storeId) {
            var me = this;

            $.ajax({
                url: Util.contextPath + '/prefs/widgetDefinition',
                type: 'POST',
                dataType: 'json',
                data: {
                    _method: 'DELETE',
                    id: storeId
                }
            })
                .done(_.bind(function(resp) {
                    me.removeStore(storeId);
                }, me))
                .fail(_.bind(function(data, textStatus, error) {
                    console.log('second delete failed');
                }, me));
        },

        removeStore: function(storeId) {
            this.storeViews[storeId].$el.fadeOut('fast');
            this.collection.remove(this.collection.get(storeId), {
                silent: true
            });
        },

        refresh: function() {
            this.$el.empty();
            this.render();
        },

        remove: function() {
            _.invoke(_.values(this.storeViews), 'remove');
            delete this.storeViews;
            delete this.descriptionView;
            delete this.button;

            Backbone.View.prototype.remove.call(this);
        }
    });
});